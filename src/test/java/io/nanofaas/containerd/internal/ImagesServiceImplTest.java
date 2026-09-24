package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Platform;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImagesServiceImplTest {

    @Test
    void sendsRegistrySourceAndImageStoreDestination() throws Exception {
        AtomicReference<containerd.services.transfer.v1.TransferRequest> captured = new AtomicReference<>();
        String name = InProcessServerBuilder.generateName();

        InProcessServerBuilder.forName(name).directExecutor()
                .addService(containerd.services.transfer.v1.TransferGrpc.bindService(
                        new containerd.services.transfer.v1.TransferGrpc.TransferImplBase() {
                            @Override
                            public void transfer(containerd.services.transfer.v1.TransferRequest request,
                                                 StreamObserver<com.google.protobuf.Empty> responseObserver) {
                                captured.set(request);
                                responseObserver.onNext(com.google.protobuf.Empty.getDefaultInstance());
                                responseObserver.onCompleted();
                            }
                        }))
                .build().start();

        try {
            var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                new ImagesServiceImpl(channel, "overlayfs")
                        .pull("docker.io/library/alpine:latest", Platform.linuxAmd64());

                Any source = captured.get().getSource();
                Any destination = captured.get().getDestination();

                assertThat(source.getTypeUrl()).isEqualTo("containerd.types.transfer.OCIRegistry");
                var registry = containerd.types.transfer.OCIRegistry.parseFrom(source.getValue());
                assertThat(registry.getReference()).isEqualTo("docker.io/library/alpine:latest");

                assertThat(destination.getTypeUrl()).isEqualTo("containerd.types.transfer.ImageStore");
                var store = containerd.types.transfer.ImageStore.parseFrom(destination.getValue());
                assertThat(store.getName()).isEqualTo("docker.io/library/alpine:latest");
                assertThat(store.getAllMetadata()).isTrue();
                assertThat(store.getPlatformsList()).hasSize(1);
                assertThat(store.getPlatforms(0).getOs()).isEqualTo("linux");
                assertThat(store.getPlatforms(0).getArchitecture()).isEqualTo("amd64");
                assertThat(store.getUnpacksList()).hasSize(1);
                assertThat(store.getUnpacks(0).getSnapshotter()).isEqualTo("overlayfs");
                assertThat(store.getUnpacks(0).getPlatform().getArchitecture()).isEqualTo("amd64");
            } finally {
                channel.shutdownNow();
            }
        } finally {
            InProcessServerBuilder.forName(name).build().shutdownNow();
        }
    }
}
