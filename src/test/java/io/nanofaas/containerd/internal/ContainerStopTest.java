package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerStopException;
import io.nanofaas.containerd.Signal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * stop() must stay bounded. SIGKILL cannot be caught, but a task wedged in uninterruptible
 * sleep (a hung NFS or fuse mount is the usual cause) never reaps, and a Wait with no deadline
 * would leave the caller blocked for good.
 */
class ContainerStopTest {

    /** A Tasks service whose Wait never answers, so only a client deadline ends the call. */
    private static final class WedgedTask implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final List<Integer> signals = new CopyOnWriteArrayList<>();
        volatile boolean deleteFailsWhileRunning = true;
        /** When set, Wait answers with this exit code instead of hanging. */
        volatile Integer exitsWith;
        final java.util.concurrent.atomic.AtomicInteger deletes = new java.util.concurrent.atomic.AtomicInteger();

        WedgedTask() throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name)
                    .addService(new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void get(containerd.services.containers.v1.GetContainerRequest request,
                                        StreamObserver<containerd.services.containers.v1.GetContainerResponse> o) {
                            o.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                    .setContainer(containerd.services.containers.v1.Container.newBuilder().setId(request.getId())).build());
                            o.onCompleted();
                        }
                    })
                    .addService(containerd.services.tasks.v1.TasksGrpc.bindService(
                            new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                                @Override
                                public void get(containerd.services.tasks.v1.GetRequest request,
                                                StreamObserver<containerd.services.tasks.v1.GetResponse> o) {
                                    o.onNext(containerd.services.tasks.v1.GetResponse.newBuilder()
                                            .setProcess(containerd.v1.types.Process.newBuilder()
                                                    .setContainerId(request.getContainerId())
                                                    .setPid(1234)
                                                    .setStatus(containerd.v1.types.Status.RUNNING))
                                            .build());
                                    o.onCompleted();
                                }

                                @Override
                                public void kill(containerd.services.tasks.v1.KillRequest request,
                                                 StreamObserver<com.google.protobuf.Empty> o) {
                                    signals.add(request.getSignal());
                                    o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                                    o.onCompleted();
                                }

                                @Override
                                public void wait(containerd.services.tasks.v1.WaitRequest request,
                                                 StreamObserver<containerd.services.tasks.v1.WaitResponse> o) {
                                    Integer code = exitsWith;
                                    if (code == null) {
                                        return; // never answers: the task is wedged and will not reap
                                    }
                                    o.onNext(containerd.services.tasks.v1.WaitResponse.newBuilder()
                                            .setExitStatus(code)
                                            .setExitedAt(com.google.protobuf.Timestamp.newBuilder()
                                                    .setSeconds(1700000000))
                                            .build());
                                    o.onCompleted();
                                }

                                @Override
                                public void delete(containerd.services.tasks.v1.DeleteTaskRequest request,
                                                   StreamObserver<containerd.services.tasks.v1.DeleteResponse> o) {
                                    deletes.incrementAndGet();
                                    if (deleteFailsWhileRunning) {
                                        // What containerd answers when asked to delete a live task.
                                        o.onError(Status.FAILED_PRECONDITION
                                                .withDescription("task must be stopped before deletion")
                                                .asRuntimeException());
                                        return;
                                    }
                                    o.onNext(containerd.services.tasks.v1.DeleteResponse.getDefaultInstance());
                                    o.onCompleted();
                                }
                            }))
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).build();
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ContainersServiceImpl service(WedgedTask fake, Duration stopTimeout) {
        return TestServices.containers(fake.channel, stopTimeout, null, TestServices.STATE_DIR);
    }

    @Test
    void returnsTheExitStatusAndReapsTheTaskWhenSigtermWorks() throws Exception {
        try (var fake = new WedgedTask()) {
            fake.exitsWith = 143; // 128 + SIGTERM
            fake.deleteFailsWhileRunning = false;

            var stopped = service(fake, Duration.ofSeconds(10)).stop("well-behaved");

            assertThat(stopped).isPresent();
            assertThat(stopped.get().code()).isEqualTo(143);
            assertThat(stopped.get().exitedAt()).isNotNull();
            assertThat(fake.signals).as("no SIGKILL needed").containsExactly(Signal.TERM.number());
            assertThat(fake.deletes.get()).as("the exited task is reaped").isEqualTo(1);
        }
    }

    @Test
    void failsInsteadOfBlockingForeverWhenTheTaskNeverExits() throws Exception {
        try (var fake = new WedgedTask()) {
            var containers = service(fake, Duration.ofMillis(300));

            // Two bounded waits (SIGTERM then SIGKILL) plus overhead; nowhere near indefinite.
            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                    assertThatThrownBy(() -> containers.stop("stuck"))
                            .isInstanceOf(ContainerStopException.class)
                            .hasMessageContaining("stuck"));
        }
    }

    @Test
    void escalatesFromSigtermToSigkillBeforeGivingUp() throws Exception {
        try (var fake = new WedgedTask()) {
            var containers = service(fake, Duration.ofMillis(300));

            assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> assertThatThrownBy(() -> containers.stop("stuck")));

            assertThat(fake.signals)
                    .as("SIGTERM first, then SIGKILL once the grace period elapses")
                    .containsExactly(Signal.TERM.number(), Signal.KILL.number());
        }
    }

    @Test
    void reportsWhyTheStopFailedRatherThanTheFailedCleanup() throws Exception {
        try (var fake = new WedgedTask()) {
            var containers = service(fake, Duration.ofMillis(300));

            // Deleting a live task fails; that failure must not replace the real diagnosis,
            // which it did when the delete ran inside a finally block.
            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                    assertThatThrownBy(() -> containers.stop("stuck"))
                            .isInstanceOf(ContainerStopException.class)
                            .hasMessageContaining("SIGKILL")
                            .hasMessageNotContaining("must be stopped before deletion"));
        }
    }
}
