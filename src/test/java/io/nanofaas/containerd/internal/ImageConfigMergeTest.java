package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an image's configuration and the caller's spec combine. The caller wins wherever they
 * expressed a wish; the image fills in the rest.
 */
class ImageConfigMergeTest {

    private static ContainerSpec.Builder spec() {
        return ContainerSpec.builder().id("c1").image("example:latest");
    }

    private static String specJson(ContainerSpec spec, ImageConfig image) {
        return OciSpecBuilder.buildContainerSpec(spec, image, List.of()).getValue().toStringUtf8();
    }

    private static ImageConfig image(List<String> entrypoint, List<String> cmd, List<String> env,
                                     String user, String workingDir) {
        return new ImageConfig(entrypoint, cmd, env, user, workingDir);
    }

    @Test
    void theImagesEntrypointAndCmdBecomeTheArgv() {
        // What most real images rely on: nothing set by the caller, everything from the image.
        String json = specJson(spec().build(),
                image(List.of("/entrypoint.sh"), List.of("postgres"), List.of(), null, null));

        assertThat(json).contains("\"args\":[\"/entrypoint.sh\",\"postgres\"]");
    }

    @Test
    void anImageWithOnlyCmdRunsThatCmd() {
        String json = specJson(spec().build(), image(List.of(), List.of("nginx", "-g", "daemon off;"),
                List.of(), null, null));

        assertThat(json).contains("\"args\":[\"nginx\",\"-g\",\"daemon off;\"]");
    }

    @Test
    void aCallersCommandReplacesTheImagesEntirely() {
        String json = specJson(spec().command(List.of("/bin/sh", "-c", "id")).build(),
                image(List.of("/entrypoint.sh"), List.of("postgres"), List.of(), null, null));

        assertThat(json).contains("\"args\":[\"/bin/sh\",\"-c\",\"id\"]")
                .doesNotContain("/entrypoint.sh");
    }

    @Test
    void withNeitherAnImageNorACommandTheProcessFallsBackToAShell() {
        assertThat(specJson(spec().build(), ImageConfig.EMPTY)).contains("\"args\":[\"/bin/sh\"]");
    }

    @Test
    void theImagesPathWinsOverThisLibrarysDefault() {
        // A JDK image puts its own java on PATH. Shadowing that with our default would leave the
        // image's entrypoint unable to find the runtime it ships.
        String json = specJson(spec().build(),
                image(List.of(), List.of(), List.of("PATH=/opt/java/openjdk/bin:/usr/bin"), null, null));

        assertThat(json).contains("\"PATH=/opt/java/openjdk/bin:/usr/bin\"")
                .doesNotContain("\"PATH=/usr/local/sbin");
    }

    @Test
    void theCallersEnvironmentWinsOverTheImages() {
        String json = specJson(spec().environment(Map.of("SONAR_JDBC_URL", "jdbc:postgresql://x/y")).build(),
                image(List.of(), List.of(), List.of("SONAR_JDBC_URL=jdbc:h2:mem", "JAVA_HOME=/opt/java"),
                        null, null));

        assertThat(json).contains("\"SONAR_JDBC_URL=jdbc:postgresql://x/y\"")
                .doesNotContain("jdbc:h2:mem")
                .as("untouched image variables survive").contains("\"JAVA_HOME=/opt/java\"");
    }

    @Test
    void eachEnvironmentKeyAppearsExactlyOnce() {
        String json = specJson(spec().environment(Map.of("PATH", "/only/this")).build(),
                image(List.of(), List.of(), List.of("PATH=/from/image"), null, null));

        assertThat(json.split("\"PATH=", -1).length - 1)
                .as("a duplicated key leaves the runtime to pick, which differs between runtimes")
                .isEqualTo(1);
        assertThat(json).contains("\"PATH=/only/this\"");
    }

    @Test
    void theImagesWorkingDirectoryAndUserApply() {
        String json = specJson(spec().build(),
                image(List.of(), List.of(), List.of(), "1000:1000", "/opt/sonarqube"));

        assertThat(json).contains("\"cwd\":\"/opt/sonarqube\"")
                .contains("\"uid\":1000")
                .contains("\"gid\":1000");
    }

    @Test
    void theCallersWorkingDirectoryAndUserWin() {
        String json = specJson(spec().workingDir("/tmp").user("65534:65534").build(),
                image(List.of(), List.of(), List.of(), "1000:1000", "/opt/sonarqube"));

        assertThat(json).contains("\"cwd\":\"/tmp\"").contains("\"uid\":65534");
    }

    @Test
    void aBareUidWithoutAGroupIsAccepted() {
        assertThat(specJson(spec().user("1000").build(), ImageConfig.EMPTY))
                .contains("\"uid\":1000").contains("\"gid\":0");
    }

    @Test
    void aUserNameFallsBackToRootRatherThanFailingTheCreate() {
        // Images commonly declare a name ("USER postgres"). It cannot be mapped to a uid without
        // reading /etc/passwd from a filesystem that is not mounted yet, but refusing outright
        // would make every such image unusable.
        String json = specJson(spec().build(),
                image(List.of(), List.of(), List.of(), "postgres", null));

        assertThat(json).contains("\"uid\":0").contains("\"gid\":0");
    }

    @Test
    void anExecInheritsTheContainersEnvironmentAndWorkingDirectory() {
        // docker exec behaves this way, and without it nothing the image ships is on PATH.
        var container = new StoredSpec(
                List.of("PATH=/opt/java/openjdk/bin", "PGDATA=/var/lib/postgresql/data"),
                "/opt/sonarqube", null);

        String json = OciSpecBuilder.buildExecSpec(List.of("env"), Map.of(), null,
                container.env(), container.workingDir(), container.rlimits()).getValue().toStringUtf8();

        assertThat(json).contains("\"PGDATA=/var/lib/postgresql/data\"")
                .contains("\"PATH=/opt/java/openjdk/bin\"")
                .contains("\"cwd\":\"/opt/sonarqube\"");
    }

    @Test
    void anExecsOwnEnvironmentAndWorkingDirectoryStillWin() {
        var container = new StoredSpec(List.of("MODE=container"), "/opt/sonarqube", null);

        String json = OciSpecBuilder.buildExecSpec(List.of("env"), Map.of("MODE", "exec"), "/tmp",
                container.env(), container.workingDir(), container.rlimits()).getValue().toStringUtf8();

        assertThat(json).contains("\"MODE=exec\"").doesNotContain("MODE=container")
                .contains("\"cwd\":\"/tmp\"");
    }

    @Test
    void anExecInheritsTheContainersResourceLimits() {
        // The limit the caller asked for has to apply to everything running in the container, not
        // just to its entrypoint. The runtime's own default (1024 open files) is what fills the
        // gap otherwise, and nothing reports that it did.
        var spec = ContainerSpec.builder().id("limited").image("alpine").openFilesLimit(4096).build();
        var stored = StoredSpec.parse(OciSpecBuilder.buildContainerSpec(spec, ImageConfig.EMPTY, List.of()));

        String json = OciSpecBuilder.buildExecSpec(List.of("sh"), Map.of(), null,
                stored.env(), stored.workingDir(), stored.rlimits()).getValue().toStringUtf8();

        assertThat(json).contains("RLIMIT_NOFILE").contains("4096");
    }

    @Test
    void anUnreadableStoredSpecDegradesToNoInheritanceRatherThanFailing() {
        assertThat(StoredSpec.parse(null)).isEqualTo(StoredSpec.EMPTY);
        assertThat(StoredSpec.parse(com.google.protobuf.Any.newBuilder()
                .setValue(com.google.protobuf.ByteString.copyFromUtf8("not json")).build()))
                .isEqualTo(StoredSpec.EMPTY);
    }
}
