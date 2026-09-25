package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerNotFoundException;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.ExitStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** inspect() combines the container record with its task, when it has one. */
class ContainerInspectTest {

    /** One container, "c1", whose task is {@link #task} (none when null). */
    private static final class FakeDaemon implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        volatile containerd.v1.types.Process task;

        FakeDaemon() throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name).directExecutor()
                    .addService(new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void get(containerd.services.containers.v1.GetContainerRequest request,
                                        StreamObserver<containerd.services.containers.v1.GetContainerResponse> o) {
                            if (!request.getId().equals("c1")) {
                                o.onError(Status.NOT_FOUND.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                    .setContainer(containerd.services.containers.v1.Container.newBuilder()
                                            .setId("c1").setImage("alpine:latest").setSnapshotKey("c1")
                                            .setCreatedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1700000000)))
                                    .build());
                            o.onCompleted();
                        }
                    })
                    .addService(new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                        @Override
                        public void get(containerd.services.tasks.v1.GetRequest request,
                                        StreamObserver<containerd.services.tasks.v1.GetResponse> o) {
                            if (task == null) {
                                o.onError(Status.NOT_FOUND.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.tasks.v1.GetResponse.newBuilder().setProcess(task).build());
                            o.onCompleted();
                        }
                    })
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void aContainerWithoutATaskIsUnknownWithNoPid() throws Exception {
        try (var fake = new FakeDaemon()) {
            var status = TestServices.containers(fake.channel).inspect("c1");

            assertThat(status.id()).isEqualTo("c1");
            assertThat(status.image()).isEqualTo("alpine:latest");
            assertThat(status.snapshotKey()).isEqualTo("c1");
            assertThat(status.createdAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
            assertThat(status.state()).isEqualTo(ContainerState.UNKNOWN);
            assertThat(status.pid()).isEqualTo(-1);
            assertThat(status.exitStatus()).isNull();
        }
    }

    @Test
    void aStoppedTaskReportsHowItExited() throws Exception {
        try (var fake = new FakeDaemon()) {
            fake.task = containerd.v1.types.Process.newBuilder()
                    .setContainerId("c1").setPid(42).setStatus(containerd.v1.types.Status.STOPPED)
                    .setExitStatus(3).setExitedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1700000100))
                    .build();

            var status = TestServices.containers(fake.channel).inspect("c1");

            assertThat(status.state()).isEqualTo(ContainerState.STOPPED);
            assertThat(status.pid()).isEqualTo(42);
            assertThat(status.exitStatus()).isEqualTo(new ExitStatus(3, Instant.ofEpochSecond(1700000100)));
        }
    }

    @Test
    void aMissingContainerIsTyped() throws Exception {
        try (var fake = new FakeDaemon()) {
            var containers = TestServices.containers(fake.channel);
            assertThatThrownBy(() -> containers.inspect("nope")).isInstanceOf(ContainerNotFoundException.class);
        }
    }
}
