package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ExecException;
import io.nanofaas.containerd.ExecSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * End-to-end cover for exec over real FIFOs, against a fake that behaves like the containerd
 * shim: it opens the FIFO paths it was handed, drains stdin, writes the output and exits.
 * Every assertion here is about blocking behaviour, which is where this path goes wrong.
 */
class ExecPathTest {

    /** Stands in for the shim: drains stdin, then writes canned stdout/stderr, then "exits". */
    private static final class FakeShim implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final java.util.concurrent.ExecutorService shimThreads = Executors.newVirtualThreadPerTaskExecutor();
        final CountDownLatch processFinished = new CountDownLatch(1);
        final AtomicReference<String> stdinSeen = new AtomicReference<>();
        final AtomicReference<Path> fifoDir = new AtomicReference<>();

        volatile String stdout = "";
        volatile String stderr = "";
        volatile int exitCode;
        volatile boolean failExecRpc;

        private volatile Path stdinPath;
        private volatile Path stdoutPath;
        private volatile Path stderrPath;

        FakeShim() throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name)
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
                                public void exec(containerd.services.tasks.v1.ExecProcessRequest request,
                                                 StreamObserver<com.google.protobuf.Empty> o) {
                                    if (failExecRpc) {
                                        o.onError(Status.FAILED_PRECONDITION
                                                .withDescription("cannot exec").asRuntimeException());
                                        return;
                                    }
                                    stdinPath = Path.of(request.getStdin());
                                    stdoutPath = Path.of(request.getStdout());
                                    stderrPath = Path.of(request.getStderr());
                                    fifoDir.set(stdoutPath.getParent());
                                    o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                                    o.onCompleted();
                                }

                                @Override
                                public void start(containerd.services.tasks.v1.StartRequest request,
                                                  StreamObserver<containerd.services.tasks.v1.StartResponse> o) {
                                    shimThreads.submit(this::runProcess);
                                    o.onNext(containerd.services.tasks.v1.StartResponse.newBuilder()
                                            .setPid(4321).build());
                                    o.onCompleted();
                                }

                                /** What the shim does: consume stdin, emit output, exit. */
                                private void runProcess() {
                                    try {
                                        // Blocks until the client opens the write end. If the
                                        // client never opens stdin at all, this never returns —
                                        // which is exactly the hang being guarded against.
                                        try (InputStream in = Files.newInputStream(stdinPath)) {
                                            ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                            byte[] chunk = new byte[1024];
                                            int n;
                                            while ((n = in.read(chunk)) != -1) {
                                                buf.write(chunk, 0, n);
                                            }
                                            stdinSeen.set(buf.toString(StandardCharsets.UTF_8));
                                        }
                                        Files.write(stdoutPath, stdout.getBytes(StandardCharsets.UTF_8));
                                        Files.write(stderrPath, stderr.getBytes(StandardCharsets.UTF_8));
                                    } catch (Exception e) {
                                        stdinSeen.set("shim failed: " + e);
                                    } finally {
                                        processFinished.countDown();
                                    }
                                }

                                @Override
                                public void wait(containerd.services.tasks.v1.WaitRequest request,
                                                 StreamObserver<containerd.services.tasks.v1.WaitResponse> o) {
                                    try {
                                        processFinished.await(30, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    o.onNext(containerd.services.tasks.v1.WaitResponse.newBuilder()
                                            .setExitStatus(exitCode).build());
                                    o.onCompleted();
                                }

                                @Override
                                public void deleteProcess(containerd.services.tasks.v1.DeleteProcessRequest request,
                                                          StreamObserver<containerd.services.tasks.v1.DeleteResponse> o) {
                                    o.onNext(containerd.services.tasks.v1.DeleteResponse.getDefaultInstance());
                                    o.onCompleted();
                                }
                            }))
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).build();
        }

        @Override
        public void close() throws Exception {
            shimThreads.shutdownNow();
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ContainersServiceImpl service(FakeShim fake) {
        return TestServices.containers(fake.channel);
    }

    @Test
    void capturesStdoutStderrAndExitCode() throws Exception {
        try (var fake = new FakeShim()) {
            fake.stdout = "hello\n";
            fake.stderr = "oops\n";
            fake.exitCode = 3;

            var result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> service(fake).exec("c1", List.of("/bin/echo", "hello")));

            assertThat(result.exitCode()).isEqualTo(3);
            assertThat(result.stdout()).isEqualTo("hello\n");
            assertThat(result.stderr()).isEqualTo("oops\n");
        }
    }

    @Test
    void deliversStdinToTheProcess() throws Exception {
        try (var fake = new FakeShim()) {
            fake.stdout = "read it\n";

            var result = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    service(fake).exec("c1", ExecSpec.builder()
                            .command(List.of("/bin/cat"))
                            .stdin("some input\n")
                            .build()));

            assertThat(fake.stdinSeen.get()).isEqualTo("some input\n");
            assertThat(result.stdout()).isEqualTo("read it\n");
        }
    }

    @Test
    void withNoStdinTheProcessStillSeesEofInsteadOfHanging() throws Exception {
        // The regression: stdin used to be opened only when the caller supplied some, so a
        // process reading stdin blocked forever. The shim above drains stdin before it writes
        // anything, so if the write end is never opened this test never completes.
        try (var fake = new FakeShim()) {
            fake.stdout = "done\n";

            var result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> service(fake).exec("c1", List.of("/bin/true")));

            assertThat(fake.stdinSeen.get()).as("stdin closed cleanly, no data").isEmpty();
            assertThat(result.stdout()).isEqualTo("done\n");
            assertThat(result.exitCode()).isZero();
        }
    }

    @Test
    void removesTheFifosAfterwards() throws Exception {
        try (var fake = new FakeShim()) {
            fake.stdout = "x";
            assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> service(fake).exec("c1", List.of("/bin/true")));

            Path dir = fake.fifoDir.get();
            assertThat(dir).isNotNull();
            assertThat(Files.exists(dir)).as("FIFO directory %s must not be left behind", dir).isFalse();
        }
    }

    @Test
    void failsWithoutHangingWhenTheShimNeverOpensTheFifos() throws Exception {
        // Exec fails before anything opens the FIFO write ends, leaving both readers blocked in
        // open(2). The finally block has to pair them off, or exec never returns.
        try (var fake = new FakeShim()) {
            fake.failExecRpc = true;

            var containers = service(fake);
            var command = List.of("/bin/true");
            assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    assertThatThrownBy(() -> containers.exec("c1", command))
                            .isInstanceOf(ExecException.class));
        }
    }

    @Test
    void rejectsExecWhenThereIsNoTask() throws Exception {
        try (var fake = new FakeShim()) {
            var containers = TestServices.containers(fake.channel);
            fake.channel.shutdownNow(); // no task lookup possible
            var command = List.of("/bin/true");
            assertThatThrownBy(() -> containers.exec("gone", command))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
