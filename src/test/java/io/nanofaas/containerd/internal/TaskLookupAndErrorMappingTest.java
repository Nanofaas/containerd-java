package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerdException;
import io.nanofaas.containerd.ContainerState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TasksServiceImpl must never let a raw gRPC exception escape, and must look a task up in a
 * single round trip.
 */
class TaskLookupAndErrorMappingTest {

    /** A Tasks service that counts Get calls and can fail every RPC with a chosen status. */
    private static final class FakeTasks implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final AtomicInteger getCalls = new AtomicInteger();
        volatile Status failWith;
        volatile boolean taskExists = true;

        FakeTasks() throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name).directExecutor()
                    .addService(containerd.services.tasks.v1.TasksGrpc.bindService(
                            new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                                @Override
                                public void get(containerd.services.tasks.v1.GetRequest request,
                                                StreamObserver<containerd.services.tasks.v1.GetResponse> o) {
                                    getCalls.incrementAndGet();
                                    if (failWith != null) {
                                        o.onError(failWith.asRuntimeException());
                                        return;
                                    }
                                    if (!taskExists) {
                                        o.onError(Status.NOT_FOUND
                                                .withDescription("no running task").asRuntimeException());
                                        return;
                                    }
                                    o.onNext(containerd.services.tasks.v1.GetResponse.newBuilder()
                                            .setProcess(containerd.v1.types.Process.newBuilder()
                                                    .setId(request.getContainerId())
                                                    .setPid(4242)
                                                    .setStatus(containerd.v1.types.Status.RUNNING))
                                            .build());
                                    o.onCompleted();
                                }

                                @Override
                                public void list(containerd.services.tasks.v1.ListTasksRequest request,
                                                 StreamObserver<containerd.services.tasks.v1.ListTasksResponse> o) {
                                    o.onError(failWith.asRuntimeException());
                                }

                                @Override
                                public void start(containerd.services.tasks.v1.StartRequest request,
                                                  StreamObserver<containerd.services.tasks.v1.StartResponse> o) {
                                    o.onError(failWith.asRuntimeException());
                                }
                            }))
                    .build().start();
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
    void findLooksTheTaskUpInASingleRoundTrip() throws Exception {
        try (var fake = new FakeTasks()) {
            var task = TestServices.tasks(fake.channel).find("abc");

            assertThat(task).isPresent();
            assertThat(task.get().containerId()).isEqualTo("abc");
            assertThat(task.get().pid()).isEqualTo(4242);
            assertThat(task.get().state()).isEqualTo(ContainerState.RUNNING);
            assertThat(fake.getCalls.get()).as("a single Get").isEqualTo(1);
        }
    }

    @Test
    void findIsEmptyForAMissingTask() throws Exception {
        try (var fake = new FakeTasks()) {
            fake.taskExists = false;
            assertThat(TestServices.tasks(fake.channel).find("abc")).isEmpty();
            assertThat(fake.getCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void startFindAndListMapGrpcFailuresToLibraryExceptions() throws Exception {
        try (var fake = new FakeTasks()) {
            fake.failWith = Status.INTERNAL.withDescription("shim died");
            var tasks = TestServices.tasks(fake.channel);

            // Before the fix these three threw StatusRuntimeException, which no caller
            // catching ContainerdException would ever see.
            assertThatThrownBy(() -> tasks.start("abc"))
                    .isInstanceOf(ContainerdException.class).isNotInstanceOf(StatusRuntimeException.class);
            assertThatThrownBy(() -> tasks.find("abc"))
                    .isInstanceOf(ContainerdException.class).isNotInstanceOf(StatusRuntimeException.class);
            assertThatThrownBy(tasks::list)
                    .isInstanceOf(ContainerdException.class).isNotInstanceOf(StatusRuntimeException.class);
        }
    }

    @Test
    void mappedExceptionsCarryContainerdsOwnExplanation() throws Exception {
        try (var fake = new FakeTasks()) {
            fake.failWith = Status.INTERNAL.withDescription("shim died");
            assertThatThrownBy(() -> TestServices.tasks(fake.channel).start("abc"))
                    .hasMessageContaining("shim died")
                    .hasMessageContaining("INTERNAL");
        }
    }
}
