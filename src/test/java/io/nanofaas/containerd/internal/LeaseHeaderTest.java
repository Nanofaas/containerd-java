package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease reaches containerd as a header, so an interceptor dropped in a refactor would stop it
 * working without anything failing: containers would still be created, and orphaned snapshots
 * would quietly go back to living forever. These assertions are what would notice.
 */
class LeaseHeaderTest {

    private static final Metadata.Key<String> LEASE =
            Metadata.Key.of("containerd-lease", Metadata.ASCII_STRING_MARSHALLER);
    private static final String SCRATCH_CONFIG = """
            {"rootfs": {"type": "layers", "diff_ids": []}}
            """;
    private static final String SCRATCH_MANIFEST = """
            {"mediaType": "application/vnd.oci.image.manifest.v1+json",
             "config": {"digest": "sha256:config", "size": 40}, "layers": []}
            """;

    /** Records the lease header seen on each gRPC method. */
    private static final class HeaderSpy implements ServerInterceptor {
        final Map<String, String> leaseByMethod = new ConcurrentHashMap<>();

        @Override
        public <Q, P> ServerCall.Listener<Q> interceptCall(ServerCall<Q, P> call, Metadata headers,
                                                           ServerCallHandler<Q, P> next) {
            String method = call.getMethodDescriptor().getBareMethodName();
            leaseByMethod.put(method, String.valueOf(headers.get(LEASE)));
            return next.startCall(call, headers);
        }
    }

    private static final class FakeContainerd implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final HeaderSpy spy = new HeaderSpy();

        FakeContainerd() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor().intercept(spy);

            builder.addService(containerd.services.leases.v1.LeasesGrpc.bindService(
                    new containerd.services.leases.v1.LeasesGrpc.LeasesImplBase() {
                        @Override
                        public void create(containerd.services.leases.v1.CreateRequest request,
                                           StreamObserver<containerd.services.leases.v1.CreateResponse> o) {
                            o.onNext(containerd.services.leases.v1.CreateResponse.newBuilder()
                                    .setLease(containerd.services.leases.v1.Lease.newBuilder()
                                            .setId(request.getId()))
                                    .build());
                            o.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.leases.v1.DeleteRequest request,
                                           StreamObserver<com.google.protobuf.Empty> o) {
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.images.v1.ImagesGrpc.bindService(
                    new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                        @Override
                        public void get(containerd.services.images.v1.GetImageRequest request,
                                        StreamObserver<containerd.services.images.v1.GetImageResponse> o) {
                            o.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                    .setImage(containerd.services.images.v1.Image.newBuilder()
                                            .setName(request.getName())
                                            .setTarget(containerd.types.Descriptor.newBuilder()
                                                    .setDigest("sha256:manifest")
                                                    .setSize(SCRATCH_MANIFEST.length())))
                                    .build());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.content.v1.ContentGrpc.bindService(
                    new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                        private String blob(String digest) {
                            return digest.equals("sha256:config") ? SCRATCH_CONFIG : SCRATCH_MANIFEST;
                        }

                        @Override
                        public void info(containerd.services.content.v1.InfoRequest request,
                                         StreamObserver<containerd.services.content.v1.InfoResponse> o) {
                            o.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                    .setInfo(containerd.services.content.v1.Info.newBuilder()
                                            .setDigest(request.getDigest())
                                            .setSize(blob(request.getDigest()).length()))
                                    .build());
                            o.onCompleted();
                        }

                        @Override
                        public void read(containerd.services.content.v1.ReadContentRequest request,
                                         StreamObserver<containerd.services.content.v1.ReadContentResponse> o) {
                            o.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                    .setData(com.google.protobuf.ByteString
                                            .copyFromUtf8(blob(request.getDigest())))
                                    .build());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest request,
                                            StreamObserver<containerd.services.snapshots.v1.PrepareSnapshotResponse> o) {
                            o.onNext(containerd.services.snapshots.v1.PrepareSnapshotResponse
                                    .getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void create(containerd.services.containers.v1.CreateContainerRequest request,
                                           StreamObserver<containerd.services.containers.v1.CreateContainerResponse> o) {
                            o.onNext(containerd.services.containers.v1.CreateContainerResponse.newBuilder()
                                    .setContainer(request.getContainer()).build());
                            o.onCompleted();
                        }
                    }));

            this.server = builder.build().start();
            this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void theSnapshotAndTheContainerAreBothCreatedUnderTheLease() throws Exception {
        try (var fake = new FakeContainerd()) {
            TestServices.containers(fake.channel)
                    .create(ContainerSpec.builder().id("leased-1").image("scratch:latest").build());

            assertThat(fake.spy.leaseByMethod)
                    .as("the snapshot is what the lease exists to hold, and the container with it,"
                            + " so the two are torn down together if abandoned")
                    .containsEntry("Prepare", "containerd-java-create-leased-1")
                    .containsEntry("Create", "containerd-java-create-leased-1");
        }
    }

    @Test
    void theLeaseIsReleasedOnceTheContainerOwnsTheSnapshot() throws Exception {
        try (var fake = new FakeContainerd()) {
            TestServices.containers(fake.channel)
                    .create(ContainerSpec.builder().id("leased-2").image("scratch:latest").build());

            // Delete reaches the Leases service: held any longer, the lease would keep resources
            // alive that the container's own gc.ref label is already responsible for.
            assertThat(fake.spy.leaseByMethod).containsKey("Delete");
        }
    }
}
