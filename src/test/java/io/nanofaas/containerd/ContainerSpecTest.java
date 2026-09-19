package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerSpecTest {

    @Test
    void builderPopulatesFields() {
        var spec = ContainerSpec.builder()
                .id("fn-42")
                .image("docker.io/my/function:v1")
                .command(List.of("/function"))
                .environment(Map.of("PORT", "8080"))
                .cpuQuotaMicros(50000)
                .memoryLimitBytes(128L * 1024 * 1024)
                .cpuSetCpus("0-2,4")
                .memoryReservationBytes(64L * 1024 * 1024)
                .cgroupsPath("user.slice:nanofaas:fn-42")
                .build();

        assertThat(spec.id()).isEqualTo("fn-42");
        assertThat(spec.command()).containsExactly("/function");
        assertThat(spec.environment()).containsEntry("PORT", "8080");
        assertThat(spec.cpuQuotaMicros()).isEqualTo(50000);
        assertThat(spec.memoryLimitBytes()).isEqualTo(128L * 1024 * 1024);
        assertThat(spec.cpuSetCpus()).isEqualTo("0-2,4");
        assertThat(spec.memoryReservationBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(spec.cgroupsPath()).isEqualTo("user.slice:nanofaas:fn-42");
    }

    @Test
    void rootlessResourceOptionsAreUnsetByDefault() {
        var spec = ContainerSpec.builder().id("fn-42").image("alpine").build();
        assertThat(spec.cpuSetCpus()).isNull();
        assertThat(spec.memoryReservationBytes()).isZero();
        assertThat(spec.cgroupsPath()).isNull();
    }

    @Test
    void idIsRequired() {
        var builder = ContainerSpec.builder().image("alpine");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }
}
