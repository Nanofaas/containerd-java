package io.nanofaas.containerd.internal;

import com.google.protobuf.Empty;
import containerd.services.transfer.v1.TransferGrpc;
import containerd.services.transfer.v1.TransferRequest;
import containerd.types.transfer.OCIRegistry;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryHostsDirectoryTest {
    @Test
    void sendsDaemonHostsDirectoryWithoutChangingSchemeOrPlatform() throws Exception {
        var registry = pull(Path.of("/daemon/certs.d"));
        assertThat(registry.getReference()).isEqualTo("registry.example/greet:latest");
        assertThat(registry.hasResolver()).isTrue();
        assertThat(registry.getResolver().getHostDir()).isEqualTo("/daemon/certs.d");
        assertThat(registry.getResolver().getDefaultScheme()).isEmpty();
        assertThat(registry.getResolver().getAuthStream()).isEmpty();
    }

    @Test
    void leavesResolverUnsetByDefault() throws Exception {
        assertThat(pull(null).hasResolver()).isFalse();
    }

    @Test
    void builderRejectsNullAndRelativeDaemonPaths() {
        var builder = ContainerdClient.builder();
        assertThatThrownBy(() -> builder.registryHostsDirectory(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.registryHostsDirectory(Path.of("certs.d")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
        // This path exists in the daemon's filesystem, which need not be the client's.
        assertThat(builder.registryHostsDirectory(Path.of("/daemon-only/certs.d"))).isSameAs(builder);
    }

    private static OCIRegistry pull(Path hostsDirectory) throws Exception {
        var captured = new AtomicReference<TransferRequest>();
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new TransferGrpc.TransferImplBase() {
                    @Override
                    public void transfer(TransferRequest request, StreamObserver<Empty> observer) {
                        captured.set(request);
                        observer.onNext(Empty.getDefaultInstance());
                        observer.onCompleted();
                    }
                }).build().start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var images = hostsDirectory == null ? new ImagesServiceImpl(channel, "native")
                    : new ImagesServiceImpl(channel, "native", hostsDirectory);
            images.pull("registry.example/greet:latest", new Platform("linux", "arm64"));
            var destination = containerd.types.transfer.ImageStore.parseFrom(captured.get().getDestination().getValue());
            assertThat(destination.getPlatforms(0).getArchitecture()).isEqualTo("arm64");
            assertThat(destination.getUnpacks(0).getSnapshotter()).isEqualTo("native");
            return OCIRegistry.parseFrom(captured.get().getSource().getValue());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
