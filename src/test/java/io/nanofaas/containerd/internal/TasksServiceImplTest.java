package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TasksServiceImplTest {

    private static final class FakeTaskServer implements AutoCloseable {
        final AtomicReference<containerd.services.tasks.v1.CreateTaskRequest> created = new AtomicReference<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        volatile boolean failStart;

        final io.grpc.Server server;
        final io.grpc.ManagedChannel channel;

        FakeTaskServer() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor();

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void get(containerd.services.containers.v1.GetContainerRequest request,
                                        StreamObserver<containerd.services.containers.v1.GetContainerResponse> responseObserver) {
                            responseObserver.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                    .setContainer(containerd.services.containers.v1.Container.newBuilder()
                                            .setId(request.getId())
                                            .setSnapshotter("overlayfs")
                                            .setSnapshotKey(request.getId()))
                                    .build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void mounts(containerd.services.snapshots.v1.MountsRequest request,
                                           StreamObserver<containerd.services.snapshots.v1.MountsResponse> responseObserver) {
                            responseObserver.onNext(containerd.services.snapshots.v1.MountsResponse.newBuilder()
                                    .addMounts(containerd.types.Mount.newBuilder().setType("bind")
                                            .setSource("/var/lib/containerd/snap").setTarget("/")).build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.tasks.v1.TasksGrpc.bindService(
                    new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                        @Override
                        public void create(containerd.services.tasks.v1.CreateTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.CreateTaskResponse> responseObserver) {
                            created.set(request);
                            responseObserver.onNext(containerd.services.tasks.v1.CreateTaskResponse.newBuilder()
                                    .setContainerId(request.getContainerId()).setPid(42).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void start(containerd.services.tasks.v1.StartRequest request,
                                          StreamObserver<containerd.services.tasks.v1.StartResponse> responseObserver) {
                            if (failStart) {
                                responseObserver.onError(Status.INTERNAL.asRuntimeException());
                                return;
                            }
                            starts.incrementAndGet();
                            responseObserver.onNext(containerd.services.tasks.v1.StartResponse.newBuilder().setPid(42).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.tasks.v1.DeleteTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.DeleteResponse> responseObserver) {
                            deletes.incrementAndGet();
                            responseObserver.onNext(containerd.services.tasks.v1.DeleteResponse.newBuilder()
                                    .setId(request.getContainerId()).build());
                            responseObserver.onCompleted();
                        }
                    }));

            server = builder.build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void createPassesSnapshotMountsAsRootfs() throws Exception {
        try (var fake = new FakeTaskServer()) {
            new TasksServiceImpl(fake.channel).create("abc");
            var request = fake.created.get();
            assertThat(request.getContainerId()).isEqualTo("abc");
            assertThat(request.getRootfsList()).hasSize(1);
            assertThat(request.getRootfs(0).getSource()).isEqualTo("/var/lib/containerd/snap");
        }
    }

    @Test
    void createFetchesContainerFirst() throws Exception {
        try (var fake = new FakeTaskServer()) {
            new TasksServiceImpl(fake.channel).create("abc");
            assertThat(fake.created.get()).isNotNull();
        }
    }

    @Test
    void createPassesSystemdCgroupToShimWithoutRequiringABinaryOverride() throws Exception {
        try (var fake = new FakeTaskServer()) {
            new TasksServiceImpl(fake.channel, null, true).create("abc");
            var options = containerd.runc.v1.Options.parseFrom(fake.created.get().getOptions().getValue());
            assertThat(options.getSystemdCgroup()).isTrue();
            assertThat(options.getBinaryName()).isEmpty();
        }
    }
}
