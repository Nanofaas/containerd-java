package io.nanofaas.containerd.internal;

import com.google.protobuf.util.JsonFormat;
import io.nanofaas.containerd.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OciSpecBuilderTest {

    private static final String SPEC_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Spec";
    private static final String PROCESS_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Process";

    private static com.google.protobuf.Struct parse(com.google.protobuf.Any any) {
        try {
            var struct = com.google.protobuf.Struct.newBuilder();
            JsonFormat.parser().merge(any.getValue().toStringUtf8(), struct);
            return struct.build();
        } catch (Exception e) {
            throw new AssertionError("spec JSON does not parse: " + any.getValue().toStringUtf8(), e);
        }
    }

    @Test
    void containerSpecUsesContainerdTypeUrlAndJsonValue() {
        var any = OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("docker.io/library/alpine:latest")
                .command(List.of("/bin/sh", "-c", "while true; do sleep 10; done"))
                .build());

        assertThat(any.getTypeUrl()).isEqualTo(SPEC_TYPE_URL);
        assertThat(any.getValue().size()).isGreaterThan(0);
        parse(any); // must not throw
    }

    @Test
    void containerSpecIncludesProcessRootAndLinuxDefaults() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .command(List.of("/bin/sh"))
                .workingDir("/work")
                .environment(Map.of("PORT", "8080"))
                .hostname("myhost")
                .user("1000:1000")
                .build()));

        // 1.0.0, not a later version: every field built here exists in 1.0, and declaring more
        // than is true is not free — crun before 1.14.3 refuses any config claiming 1.2.x.
        assertThat(spec.getFieldsOrThrow("ociVersion").getStringValue()).isEqualTo("1.0.0");

        var process = spec.getFieldsOrThrow("process").getStructValue();
        assertThat(process.getFieldsOrThrow("cwd").getStringValue()).isEqualTo("/work");
        assertThat(process.getFieldsOrThrow("args").getListValue().getValues(0).getStringValue()).isEqualTo("/bin/sh");
        assertThat(process.getFieldsOrThrow("env").getListValue().getValuesList())
                .extracting(com.google.protobuf.Value::getStringValue)
                .contains("PORT=8080");
        assertThat(process.getFieldsOrThrow("terminal").getBoolValue()).isFalse();

        var user = process.getFieldsOrThrow("user").getStructValue();
        assertThat(user.getFieldsOrThrow("uid").getNumberValue()).isEqualTo(1000);
        assertThat(user.getFieldsOrThrow("gid").getNumberValue()).isEqualTo(1000);

        var root = spec.getFieldsOrThrow("root").getStructValue();
        assertThat(root.getFieldsOrThrow("path").getStringValue()).isEqualTo("rootfs");
        assertThat(root.getFieldsOrThrow("readonly").getBoolValue()).isFalse();

        assertThat(spec.getFieldsOrThrow("hostname").getStringValue()).isEqualTo("myhost");

        var linux = spec.getFieldsOrThrow("linux").getStructValue();
        var namespaces = linux.getFieldsOrThrow("namespaces").getListValue().getValuesList();
        assertThat(namespaces).extracting(v -> v.getStructValue().getFieldsOrThrow("type").getStringValue())
                .containsExactlyInAnyOrder("pid", "network", "ipc", "uts", "mount");
    }

    @Test
    void containerSpecIncludesResourceLimits() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .cpuShares(512)
                .cpuQuotaMicros(50000)
                .cpuPeriodMicros(100000)
                .memoryLimitBytes(128L * 1024 * 1024)
                .memorySwapLimitBytes(256L * 1024 * 1024)
                .pidsLimit(100)
                .build()));

        var resources = spec.getFieldsOrThrow("linux").getStructValue()
                .getFieldsOrThrow("resources").getStructValue();

        var cpu = resources.getFieldsOrThrow("cpu").getStructValue();
        assertThat(cpu.getFieldsOrThrow("shares").getNumberValue()).isEqualTo(512);
        assertThat(cpu.getFieldsOrThrow("quota").getNumberValue()).isEqualTo(50000);
        assertThat(cpu.getFieldsOrThrow("period").getNumberValue()).isEqualTo(100000);

        var memory = resources.getFieldsOrThrow("memory").getStructValue();
        assertThat(memory.getFieldsOrThrow("limit").getNumberValue()).isEqualTo(128L * 1024 * 1024);
        assertThat(memory.getFieldsOrThrow("swap").getNumberValue()).isEqualTo(256L * 1024 * 1024);

        assertThat(resources.getFieldsOrThrow("pids").getStructValue()
                .getFieldsOrThrow("limit").getNumberValue()).isEqualTo(100);
    }

    @Test
    void readonlyRootfsIsPropagated() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .readonlyRootfs(true)
                .build()));
        assertThat(spec.getFieldsOrThrow("root").getStructValue().getFieldsOrThrow("readonly").getBoolValue()).isTrue();
    }

    @Test
    void printedSpecJsonUsesBareIntegersForNumericFields() {
        // Regression: containerd unmarshals the spec into Go uint64/int64 fields and rejects
        // "1024.0" (protobuf Value doubles printed with a decimal point).
        var any = OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .command(List.of("/bin/sh"))
                .user("1000:1000")
                .cpuShares(512)
                .memoryLimitBytes(128L * 1024 * 1024)
                .pidsLimit(100)
                .build());
        String json = any.getValue().toStringUtf8();
        assertThat(json)
                .contains("\"hard\":1024", "\"soft\":1024", "\"uid\":1000",
                        "\"gid\":1000", "\"shares\":512", "\"limit\":134217728", "\"limit\":100")
                // no JSON number token with a fractional part (string contents are quoted, so a
                // digit right after : , or [ is always a real number)
                .doesNotMatch("[:,\\[]-?\\d+\\.\\d");
    }

    @Test
    void execSpecUsesProcessTypeUrlAndJson() {
        var any = OciSpecBuilder.buildExecSpec(List.of("/bin/echo", "hello"), Map.of("A", "B"), "/tmp");
        assertThat(any.getTypeUrl()).isEqualTo(PROCESS_TYPE_URL);
        var process = parse(any);
        assertThat(process.getFieldsOrThrow("args").getListValue().getValuesList())
                .extracting(com.google.protobuf.Value::getStringValue)
                .containsExactly("/bin/echo", "hello");
        assertThat(process.getFieldsOrThrow("cwd").getStringValue()).isEqualTo("/tmp");
    }

    @Test
    void theInitProcessAndExecAgreeOnPrivilegeEscalation() {
        // These two used to disagree: exec set noNewPrivileges true while the entrypoint set it
        // false, so "sudo" failed under exec and worked as the entrypoint. Both now match the
        // docker/ctr default of false. Changing that is a security decision, not a cleanup.
        String containerSpec = OciSpecBuilder.buildContainerSpec(
                ContainerSpec.builder().id("c1").image("scratch").build())
                .getValue().toStringUtf8();
        String execSpec = OciSpecBuilder.buildExecSpec(
                java.util.List.of("sudo", "whoami"), java.util.Map.of(), null)
                .getValue().toStringUtf8();

        assertThat(containerSpec).contains("\"noNewPrivileges\":false");
        assertThat(execSpec).contains("\"noNewPrivileges\":false");
    }

    @Test
    void containersGetTheirOwnNetworkNamespaceByDefault() {
        String json = OciSpecBuilder.buildContainerSpec(
                ContainerSpec.builder().id("c1").image("scratch").build())
                .getValue().toStringUtf8();

        assertThat(json).contains("\"type\":\"network\"");
    }

    @Test
    void hostNetworkOmitsTheNetworkNamespaceAndKeepsTheOthers() {
        // The OCI spec has no "use the host's network" switch: a namespace is requested by being
        // listed, so sharing the host's means leaving it out.
        String json = OciSpecBuilder.buildContainerSpec(
                ContainerSpec.builder().id("c1").image("scratch").hostNetwork(true).build())
                .getValue().toStringUtf8();

        assertThat(json).doesNotContain("\"type\":\"network\"")
                .contains("\"type\":\"pid\"")
                .contains("\"type\":\"ipc\"")
                .contains("\"type\":\"uts\"")
                .contains("\"type\":\"mount\"");
    }

    @Test
    void theOpenFileLimitDefaultsToAThousandAndTwentyFour() {
        String json = OciSpecBuilder.buildContainerSpec(
                ContainerSpec.builder().id("c1").image("scratch").build())
                .getValue().toStringUtf8();

        assertThat(json).contains("\"type\":\"RLIMIT_NOFILE\"")
                .contains("\"hard\":1024").contains("\"soft\":1024");
    }

    @Test
    void theOpenFileLimitIsConfigurable() {
        // Elasticsearch refuses to start below 65535, and the default of 1024 is what stopped
        // SonarQube from coming up.
        String json = OciSpecBuilder.buildContainerSpec(
                ContainerSpec.builder().id("c1").image("scratch").openFilesLimit(65536).build())
                .getValue().toStringUtf8();

        assertThat(json).contains("\"hard\":65536").contains("\"soft\":65536")
                .doesNotContain("1024");
    }

    @Test
    void aNonPositiveOpenFileLimitIsRejected() {
        var builder = ContainerSpec.builder().id("c1").image("scratch");
        assertThatThrownBy(() -> builder.openFilesLimit(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    @Test
    void rootlessResourceKnobsReachTheOciSpec() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("resources").image("alpine").cpuSetCpus("0-1")
                .memoryReservationBytes(67108864).memoryLimitBytes(134217728)
                .cpuShares(512).cpuQuotaMicros(50000).cpuPeriodMicros(100000)
                .cgroupsPath("user.slice:nanofaas:resources").build()));
        var linux = spec.getFieldsOrThrow("linux").getStructValue();
        assertThat(linux.getFieldsOrThrow("cgroupsPath").getStringValue()).isEqualTo("user.slice:nanofaas:resources");
        var resources = linux.getFieldsOrThrow("resources").getStructValue();
        var cpu = resources.getFieldsOrThrow("cpu").getStructValue();
        assertThat(cpu.getFieldsOrThrow("cpus").getStringValue()).isEqualTo("0-1");
        assertThat(cpu.getFieldsOrThrow("shares").getNumberValue()).isEqualTo(512);
        assertThat(cpu.getFieldsOrThrow("quota").getNumberValue()).isEqualTo(50000);
        assertThat(cpu.getFieldsOrThrow("period").getNumberValue()).isEqualTo(100000);
        assertThat(resources.getFieldsOrThrow("memory").getStructValue()
                .getFieldsOrThrow("reservation").getNumberValue()).isEqualTo(67108864);
    }
}
