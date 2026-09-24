package io.nanofaas.containerd.spi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerdClientBuilderTest {

    @Test
    void theClientReportsWhatItWasBuiltWith() {
        // Nothing listens on the socket: the channel connects lazily, on the first call.
        try (var client = ContainerdClient.builder()
                .socketPath("/nonexistent/containerd.sock")
                .namespace("tests")
                .snapshotter("native")
                .runtimeName("io.containerd.runc.v2")
                .runtimeBinaryName("crun")
                .systemdCgroup(true)
                .stopTimeout(Duration.ofSeconds(3))
                .stateDirectory(Path.of(System.getProperty("java.io.tmpdir"), "containerd-java-builder-test"))
                .build()) {
            assertThat(client.namespace()).isEqualTo("tests");
            assertThat(client.snapshotter()).isEqualTo("native");
            assertThat(client.runtimeName()).isEqualTo("io.containerd.runc.v2");
        }
    }

    @Test
    void defaultsMatchContainerdsOwn() {
        try (var client = ContainerdClient.builder().build()) {
            assertThat(client.namespace()).isEqualTo("nanofaas");
            assertThat(client.snapshotter()).isEqualTo("overlayfs");
            assertThat(client.runtimeName()).isEqualTo("io.containerd.runc.v2");
        }
    }

    @Test
    void rejectsAStopTimeoutThatIsNotPositive() {
        var builder = ContainerdClient.builder();
        assertThatThrownBy(() -> builder.stopTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.stopTimeout(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullsWhereThereIsADefaultToKeep() {
        var builder = ContainerdClient.builder();
        assertThatThrownBy(() -> builder.socketPath(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.namespace(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.snapshotter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.runtimeName(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.stopTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.network(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.stateDirectory(null)).isInstanceOf(NullPointerException.class);
    }
}
