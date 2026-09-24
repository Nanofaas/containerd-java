package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerdException;
import io.nanofaas.containerd.ImageNotFoundException;
import io.nanofaas.containerd.ImagePullException;
import io.nanofaas.containerd.Platform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** An image store holding one image, or failing every call with {@link #failWith}. */
    private static final class FakeImageStore implements AutoCloseable {
        static final containerd.services.images.v1.Image ALPINE = containerd.services.images.v1.Image.newBuilder()
                .setName("docker.io/library/alpine:latest")
                .setTarget(containerd.types.Descriptor.newBuilder().setDigest("sha256:abc").setSize(1234))
                .setCreatedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1700000000))
                .putLabels("owner", "test")
                .build();

        final io.grpc.Server server;
        final ManagedChannel channel;
        volatile Status failWith;

        FakeImageStore() throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name).directExecutor()
                    .addService(new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                        @Override
                        public void get(containerd.services.images.v1.GetImageRequest request,
                                        StreamObserver<containerd.services.images.v1.GetImageResponse> o) {
                            if (failed(o)) {
                                return;
                            }
                            o.onNext(containerd.services.images.v1.GetImageResponse.newBuilder().setImage(ALPINE).build());
                            o.onCompleted();
                        }

                        @Override
                        public void list(containerd.services.images.v1.ListImagesRequest request,
                                         StreamObserver<containerd.services.images.v1.ListImagesResponse> o) {
                            if (failed(o)) {
                                return;
                            }
                            o.onNext(containerd.services.images.v1.ListImagesResponse.newBuilder().addImages(ALPINE).build());
                            o.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.images.v1.DeleteImageRequest request,
                                           StreamObserver<com.google.protobuf.Empty> o) {
                            if (failed(o)) {
                                return;
                            }
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    })
                    .addService(new containerd.services.transfer.v1.TransferGrpc.TransferImplBase() {
                        @Override
                        public void transfer(containerd.services.transfer.v1.TransferRequest request,
                                             StreamObserver<com.google.protobuf.Empty> o) {
                            if (failed(o)) {
                                return;
                            }
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    })
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        private boolean failed(StreamObserver<?> o) {
            if (failWith == null) {
                return false;
            }
            o.onError(failWith.asRuntimeException());
            return true;
        }

        ImagesServiceImpl images() {
            return new ImagesServiceImpl(channel, "overlayfs");
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void getAndListMapTheStoredImage() throws Exception {
        try (var fake = new FakeImageStore()) {
            var image = fake.images().get("docker.io/library/alpine:latest");
            assertThat(image.name()).isEqualTo("docker.io/library/alpine:latest");
            assertThat(image.digest()).isEqualTo("sha256:abc");
            assertThat(image.size()).isEqualTo(1234);
            assertThat(image.createdAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
            assertThat(image.labels()).isEqualTo(Map.of("owner", "test"));
            assertThat(fake.images().list()).containsExactly(image);
        }
    }

    @Test
    void aMissingImageIsTypedOnGetAndIgnoredOnRemove() throws Exception {
        try (var fake = new FakeImageStore()) {
            fake.failWith = Status.NOT_FOUND;
            var images = fake.images();
            assertThatThrownBy(() -> images.get("gone:latest")).isInstanceOf(ImageNotFoundException.class);
            images.remove("gone:latest"); // idempotent: nothing thrown
        }
    }

    @Test
    void otherFailuresAreMappedNotSwallowed() throws Exception {
        try (var fake = new FakeImageStore()) {
            fake.failWith = Status.UNAVAILABLE.withDescription("daemon restarting");
            var images = fake.images();
            assertThatThrownBy(images::list).isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("daemon restarting");
            assertThatThrownBy(() -> images.remove("alpine:latest")).isInstanceOf(ContainerdException.class)
                    .isNotInstanceOf(ImageNotFoundException.class);
            assertThatThrownBy(() -> images.pull("alpine:latest"))
                    .isInstanceOf(ImagePullException.class)
                    .hasMessageContaining("alpine:latest");
        }
    }
}
