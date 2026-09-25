package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ContainerStartException;
import io.nanofaas.containerd.ContainerdException;
import io.nanofaas.containerd.NetworkAttachment;
import io.nanofaas.containerd.RemoveOptions;
import io.nanofaas.containerd.spi.ContainerNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When the network is attached and detached, relative to the task's life.
 *
 * <p>The ordering is the whole point. A container's network namespace is its task's, so it exists
 * only between start and teardown; attaching too early or detaching too late finds nothing there
 * and leaves an address allocated on the host that nothing will ever reclaim. These tests record
 * the order of every call rather than just that each happened.
 */
class ContainerNetworkLifecycleTest {

    private static final int PID = 4242;

    /** Records what it was asked to do, in order, and can be told to fail. */
    private static final class RecordingNetwork implements ContainerNetwork {
        final List<String> events;
        volatile boolean failAttach;
        volatile boolean failDetach;

        RecordingNetwork(List<String> events) {
            this.events = events;
        }

        volatile NetworkAttachment attachment = NetworkAttachment.EMPTY;

        @Override
        public NetworkAttachment attach(String containerId, String network, int pid) {
            events.add("attach:" + network + ":" + pid);
            if (failAttach) {
                throw new IllegalStateException("no address left in the pool");
            }
            return attachment;
        }

        @Override
        public void detach(String containerId, String network, int pid) {
            events.add("detach:" + network + ":" + pid);
            if (failDetach) {
                throw new IllegalStateException("plugin exploded");
            }
        }
    }

    private static final String SCRATCH_CONFIG = """
            {"rootfs": {"type": "layers", "diff_ids": []}}
            """;
    private static final String SCRATCH_MANIFEST = """
            {"mediaType": "application/vnd.oci.image.manifest.v1+json",
             "config": {"digest": "sha256:config", "size": 40}, "layers": []}
            """;

    /** A containerd that remembers the container it was given, and records task calls in order. */
    private static final class FakeContainerd implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile containerd.services.containers.v1.Container stored;
        volatile containerd.v1.types.Status taskStatus = containerd.v1.types.Status.RUNNING;
        volatile boolean taskExists;
        volatile boolean failSnapshotRemove;
        volatile boolean snapshotExists;
        volatile boolean unavailable;
        volatile boolean failTaskLookup;
        volatile boolean rejectTaskCreate;
        volatile boolean failTaskStart;
        volatile boolean deadlineOnWait;
        volatile boolean failTaskDelete;

        FakeContainerd() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor();

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
                        private String blob(String d) {
                            return d.equals("sha256:config") ? SCRATCH_CONFIG : SCRATCH_MANIFEST;
                        }

                        @Override
                        public void info(containerd.services.content.v1.InfoRequest request,
                                         StreamObserver<containerd.services.content.v1.InfoResponse> o) {
                            o.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                    .setInfo(containerd.services.content.v1.Info.newBuilder()
                                            .setDigest(request.getDigest())
                                            .setSize(blob(request.getDigest()).length())).build());
                            o.onCompleted();
                        }

                        @Override
                        public void read(containerd.services.content.v1.ReadContentRequest request,
                                         StreamObserver<containerd.services.content.v1.ReadContentResponse> o) {
                            o.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                    .setData(com.google.protobuf.ByteString.copyFromUtf8(
                                            blob(request.getDigest()))).build());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest request,
                                            StreamObserver<containerd.services.snapshots.v1.PrepareSnapshotResponse> o) {
                            snapshotExists = true;
                            o.onNext(containerd.services.snapshots.v1.PrepareSnapshotResponse
                                    .getDefaultInstance());
                            o.onCompleted();
                        }

                        @Override
                        public void mounts(containerd.services.snapshots.v1.MountsRequest request,
                                           StreamObserver<containerd.services.snapshots.v1.MountsResponse> o) {
                            o.onNext(containerd.services.snapshots.v1.MountsResponse.newBuilder()
                                    .addMounts(containerd.types.Mount.newBuilder().setType("bind")).build());
                            o.onCompleted();
                        }

                        @Override
                        public void remove(containerd.services.snapshots.v1.RemoveSnapshotRequest request,
                                           StreamObserver<com.google.protobuf.Empty> o) {
                            if (failSnapshotRemove) {
                                o.onError(Status.INTERNAL.withDescription("snapshot busy").asRuntimeException());
                                return;
                            }
                            snapshotExists = false;
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void create(containerd.services.containers.v1.CreateContainerRequest request,
                                           StreamObserver<containerd.services.containers.v1.CreateContainerResponse> o) {
                            stored = request.getContainer();
                            o.onNext(containerd.services.containers.v1.CreateContainerResponse.newBuilder()
                                    .setContainer(stored).build());
                            o.onCompleted();
                        }

                        @Override
                        public void get(containerd.services.containers.v1.GetContainerRequest request,
                                        StreamObserver<containerd.services.containers.v1.GetContainerResponse> o) {
                            if (unavailable) {
                                o.onError(Status.UNAVAILABLE.asRuntimeException());
                                return;
                            }
                            if (stored == null) {
                                o.onError(Status.NOT_FOUND.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                    .setContainer(stored).build());
                            o.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.containers.v1.DeleteContainerRequest request,
                                           StreamObserver<com.google.protobuf.Empty> o) {
                            stored = null;
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.tasks.v1.TasksGrpc.bindService(
                    new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                        @Override
                        public void create(containerd.services.tasks.v1.CreateTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.CreateTaskResponse> o) {
                            if (rejectTaskCreate) {
                                taskExists = true; // A task appeared between Get and Create.
                                taskStatus = containerd.v1.types.Status.PAUSED;
                                o.onError(Status.ALREADY_EXISTS.withDescription("existing task").asRuntimeException());
                                return;
                            }
                            if (taskExists) {
                                o.onError(Status.ALREADY_EXISTS.withDescription("existing task").asRuntimeException());
                                return;
                            }
                            taskExists = true;
                            events.add("task-create");
                            o.onNext(containerd.services.tasks.v1.CreateTaskResponse.getDefaultInstance());
                            o.onCompleted();
                        }

                        @Override
                        public void start(containerd.services.tasks.v1.StartRequest request,
                                          StreamObserver<containerd.services.tasks.v1.StartResponse> o) {
                            events.add("task-start");
                            if (failTaskStart) {
                                o.onError(Status.INTERNAL.withDescription("start failed").asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.tasks.v1.StartResponse.newBuilder()
                                    .setPid(PID).build());
                            o.onCompleted();
                        }

                        @Override
                        public void get(containerd.services.tasks.v1.GetRequest request,
                                        StreamObserver<containerd.services.tasks.v1.GetResponse> o) {
                            if (failTaskLookup) {
                                o.onError(Status.INTERNAL.withDescription("shim died").asRuntimeException());
                                return;
                            }
                            if (!taskExists || stored == null) {
                                o.onError(Status.NOT_FOUND.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.tasks.v1.GetResponse.newBuilder()
                                    .setProcess(containerd.v1.types.Process.newBuilder()
                                            .setContainerId(request.getContainerId())
                                            .setPid(PID).setStatus(taskStatus))
                                    .build());
                            o.onCompleted();
                        }

                        @Override
                        public void list(containerd.services.tasks.v1.ListTasksRequest request,
                                         StreamObserver<containerd.services.tasks.v1.ListTasksResponse> o) {
                            var result = containerd.services.tasks.v1.ListTasksResponse.newBuilder();
                            if (taskExists) result.addTasks(containerd.v1.types.Process.newBuilder()
                                    .setId("net-1").setPid(PID).setStatus(taskStatus));
                            o.onNext(result.build());
                            o.onCompleted();
                        }

                        @Override
                        public void kill(containerd.services.tasks.v1.KillRequest request,
                                         StreamObserver<com.google.protobuf.Empty> o) {
                            events.add("kill:" + request.getSignal());
                            if (request.getSignal() == 9 || !deadlineOnWait) {
                                taskStatus = containerd.v1.types.Status.STOPPED;
                            }
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }

                        @Override
                        public void wait(containerd.services.tasks.v1.WaitRequest request,
                                         StreamObserver<containerd.services.tasks.v1.WaitResponse> o) {
                            if (deadlineOnWait) {
                                o.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.tasks.v1.WaitResponse.newBuilder()
                                    .setExitStatus(0).build());
                            o.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.tasks.v1.DeleteTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.DeleteResponse> o) {
                            if (stored == null) {
                                o.onError(Status.NOT_FOUND.withDescription("container metadata missing").asRuntimeException());
                                return;
                            }
                            if (failTaskDelete) {
                                o.onError(Status.INTERNAL.withDescription("task deletion failed").asRuntimeException());
                                return;
                            }
                            taskExists = false;
                            events.add("task-delete");
                            o.onNext(containerd.services.tasks.v1.DeleteResponse.getDefaultInstance());
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

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path state;

    private ContainersServiceImpl service(FakeContainerd fake, ContainerNetwork network) {
        return service(fake, network, state);
    }

    private static ContainersServiceImpl service(FakeContainerd fake, ContainerNetwork network,
                                                 java.nio.file.Path stateDirectory) {
        return TestServices.containers(fake.channel, java.time.Duration.ofSeconds(1), network, stateDirectory);
    }

    private static ContainerSpec networked() {
        return ContainerSpec.builder().id("net-1").image("scratch:latest").network("mynet").build();
    }

    @Test
    void attachesOnlyOnceTheTaskIsRunningAndWithItsPid() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());

            containers.start("net-1");

            assertThat(fake.events)
                    .as("the namespace to attach to does not exist until the task does")
                    .containsExactly("task-create", "task-start", "attach:mynet:" + PID);
        }
    }

    @Test
    void detachesBeforeAnythingIsKilled() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.events.clear();

            containers.stop("net-1");

            assertThat(fake.events).isNotEmpty();
            assertThat(fake.events.get(0))
                    .as("detaching after the kill would find no namespace and leak the address")
                    .isEqualTo("detach:mynet:" + PID);
            assertThat(fake.events).contains("kill:15");
        }
    }

    @Test
    void aFailedAttachTearsTheTaskDownRatherThanLeavingItUnreachable() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.failAttach = true;
            var containers = service(fake, net);
            containers.create(networked());

            assertThatThrownBy(() -> containers.start("net-1"))
                    .isInstanceOf(ContainerStartException.class)
                    .hasMessageContaining("mynet");

            assertThat(fake.events)
                    .as("detach runs first: a plugin that failed part-way may already hold an address")
                    .containsSubsequence("attach:mynet:" + PID, "detach:mynet:" + PID, "task-delete");
        }
    }

    @Test
    void aFailingDetachIsReportedAfterStoppingTheTask() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.failDetach = true;
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");

            assertThatThrownBy(() -> containers.stop("net-1"))
                    .isInstanceOf(ContainerdException.class);
            assertThat(fake.taskExists).isFalse();
            assertThat(fake.events).contains("kill:15");
        }
    }

    @Test
    void anAlreadyExitedTaskIsStillDetached() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.taskStatus = containerd.v1.types.Status.STOPPED;
            fake.events.clear();

            containers.remove("net-1", RemoveOptions.builder().build());

            assertThat(fake.events)
                    .as("its namespace is gone, but the address it held is not")
                    .contains("detach:mynet:-1");
        }
    }

    @Test
    void aContainerWithoutANetworkIsLeftAlone() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(ContainerSpec.builder().id("net-1").image("scratch:latest").build());

            containers.start("net-1");
            containers.stop("net-1");

            assertThat(fake.events).noneMatch(e -> e.startsWith("attach") || e.startsWith("detach"));
        }
    }

    @Test
    void askingForANetworkWithoutAnImplementationIsRefusedAtCreate() throws Exception {
        try (var fake = new FakeContainerd()) {
            // Refused rather than ignored: a container that asked to be on a network and silently
            // is not is worse than one that never started.
            var containers = service(fake, null);
            var spec = networked();

            assertThatThrownBy(() -> containers.create(spec))
                    .isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("ContainerNetwork");
        }
    }

    @Test
    void theNetworkIsRememberedOnTheContainerNotInMemory() throws Exception {
        try (var fake = new FakeContainerd()) {
            service(fake, new RecordingNetwork(fake.events)).create(networked());

            assertThat(fake.stored.getLabelsMap())
                    .as("detaching can happen from a different process than the one that attached")
                    .containsEntry("io.nanofaas.containerd/cni.network", "mynet");
        }
    }

    @Test
    void hostNetworkAndANetworkCannotBothBeAsked() {
        assertThatThrownBy(() -> ContainerSpec.builder().id("net-1").image("scratch:latest")
                .hostNetwork(true).network("mynet").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void theNetworksDnsIsWrittenWhereTheContainerWillReadIt() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.attachment = new NetworkAttachment(
                    List.of("10.99.0.7/16"), List.of("10.99.0.1"),
                    List.of("10.99.0.1", "1.1.1.1"), List.of("nanofaas.local"), "nanofaas.local");
            var containers = service(fake, net);
            containers.create(networked());

            containers.start("net-1");

            // CNI reports DNS; applying it is the runtime's job, and without this a container has
            // an address and a route and still cannot resolve a name.
            String resolvConf = fake.stored.getLabelsMap()
                    .get("io.nanofaas.containerd/dns.resolvconf");
            assertThat(resolvConf).as("the file to fill in is decided at create time").isNotNull();
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(resolvConf)))
                    .contains("nameserver 10.99.0.1")
                    .contains("nameserver 1.1.1.1")
                    .contains("search nanofaas.local");
        }
    }

    @Test
    void aNetworkThatReportsNoDnsLeavesTheFileEmpty() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.attachment = NetworkAttachment.EMPTY;
            var containers = service(fake, net);
            containers.create(networked());

            containers.start("net-1");

            String resolvConf = fake.stored.getLabelsMap()
                    .get("io.nanofaas.containerd/dns.resolvconf");
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(resolvConf))).isEmpty();
        }
    }

    @Test
    void theStateDirectoryIsWhereTheContainersFilesGo() throws Exception {
        // What production needs: the mount points at this file, so it has to live somewhere that
        // outlasts a reboot rather than under a temporary directory that will not.
        java.nio.file.Path state = java.nio.file.Files.createTempDirectory("state-dir-test");
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.attachment = new NetworkAttachment(List.of("10.99.0.9/16"), List.of("10.99.0.1"),
                    List.of("10.99.0.1"), List.of(), null);
            var containers = service(fake, net, state);
            containers.create(networked());
            containers.start("net-1");

            java.nio.file.Path expected = state.resolve("net-1").resolve("resolv.conf");
            assertThat(expected).exists();
            assertThat(java.nio.file.Files.readString(expected)).contains("nameserver 10.99.0.1");
            assertThat(fake.stored.getLabelsMap())
                    .as("the path is recorded so a later process can find the same file")
                    .containsEntry("io.nanofaas.containerd/dns.resolvconf", expected.toString());
        }
    }

    @Test
    void removingTheContainerTakesItsStateWithIt() throws Exception {
        java.nio.file.Path state = java.nio.file.Files.createTempDirectory("state-dir-cleanup");
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events), state);
            containers.create(networked());
            containers.start("net-1");
            assertThat(state.resolve("net-1")).exists();

            containers.remove("net-1", RemoveOptions.builder().force(true).build());

            assertThat(state.resolve("net-1"))
                    .as("a persistent state directory would otherwise fill up with dead containers")
                    .doesNotExist();
        }
    }

    @Test
    void anUnusableStateDirectorySaysWhatToDoAboutIt() throws Exception {
        // Its parent is a regular file, so the directory cannot be created. In production this is
        // a wrong path or a permission problem, and the message has to name the way out.
        java.nio.file.Path notADirectory = java.nio.file.Files.createTempFile("not-a-dir", "");
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events),
                    notADirectory.resolve("state"));
            var spec = networked();

            assertThatThrownBy(() -> containers.create(spec))
                    .isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("stateDirectory");
        }
    }

    @Test
    void attachmentSurvivesClientRestart() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.attachment = new NetworkAttachment(List.of("10.90.0.2/24"), List.of("10.90.0.1"),
                    List.of("1.1.1.1"), List.of("test.local"), "test.local");
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            containers.close();
            assertThat(service(fake, net).networkAttachment("net-1")).isEqualTo(net.attachment);
        }
    }

    @Test
    void failedDetachRemainsDiscoverableWithNoTaskOrDaemonMetadata() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.taskExists = false;
            net.failDetach = true;
            var remove = RemoveOptions.builder().force(true).removeSnapshot(true).build();
            assertThatThrownBy(() -> containers.remove("net-1", remove)).isInstanceOf(ContainerdException.class);
            assertThat(fake.stored).isNull();
            assertThat(fake.snapshotExists).isFalse();
            var restarted = service(fake, net);
            assertThat(restarted.pendingRemovals()).extracting(io.nanofaas.containerd.Container::id).containsExactly("net-1");
            assertThat(restarted.pendingRemovals().getFirst().labels()).containsEntry(ContainersServiceImpl.NETWORK_LABEL, "mynet");
            net.failDetach = false;
            restarted.remove("net-1", remove);
            restarted.remove("net-1", remove);
            assertThat(restarted.pendingRemovals()).isEmpty();
            assertThat(state.resolve("net-1")).doesNotExist();
        }
    }

    @Test
    void failedSnapshotRemovalSurvivesMetadataDeletionAndClientRestart() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            fake.failSnapshotRemove = true;
            var remove = RemoveOptions.builder().removeSnapshot(true).build();
            assertThatThrownBy(() -> containers.remove("net-1", remove)).isInstanceOf(ContainerdException.class);
            assertThat(fake.stored).isNull();
            var restarted = service(fake, net);
            assertThat(restarted.pendingRemovals()).extracting(io.nanofaas.containerd.Container::snapshotKey).containsExactly("net-1");
            fake.failSnapshotRemove = false;
            restarted.remove("net-1", RemoveOptions.builder().build());
            assertThat(fake.snapshotExists).isFalse();
            assertThat(restarted.pendingRemovals()).isEmpty();
        }
    }

    @Test
    void partialAddKeepsPrimaryFailureAndJournalUntilDetachCanSucceed() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            net.failAttach = true;
            net.failDetach = true;
            assertThatThrownBy(() -> containers.start("net-1"))
                    .isInstanceOf(ContainerStartException.class)
                    .hasRootCauseMessage("no address left in the pool")
                    .satisfies(error -> assertThat(error.getCause().getSuppressed()).hasSize(1));
            assertThat(fake.taskExists).isFalse();
            assertThat(service(fake, net).pendingRemovals()).extracting(io.nanofaas.containerd.Container::id).containsExactly("net-1");
            net.failDetach = false;
            containers.remove("net-1", RemoveOptions.builder().removeSnapshot(true).build());
            assertThat(containers.pendingRemovals()).isEmpty();
        }
    }

    @Test
    void metadataGoneBeforeRemoveStillUsesDurableNetworkAndSnapshotIdentity() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.stored = null;
            fake.taskExists = false;
            containers.remove("net-1", RemoveOptions.builder().removeSnapshot(true).build());
            assertThat(fake.events).contains("detach:mynet:-1");
            assertThat(fake.snapshotExists).isFalse();
            assertThat(state.resolve("net-1")).doesNotExist();
        }
    }

    @Test
    void unavailableDaemonIsNotAnAbsentAttachment() throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            assertThat(containers.networkAttachment("net-1")).isNull();
            fake.unavailable = true;
            assertThatThrownBy(() -> containers.networkAttachment("net-1")).isInstanceOf(ContainerdException.class);
        }
    }

    @Test
    void pendingCleanupCannotBeOverwrittenByCreateOrStart() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            net.failDetach = true;
            assertThatThrownBy(() -> containers.remove("net-1", RemoveOptions.builder().force(true).build()))
                    .isInstanceOf(ContainerdException.class);
            assertThatThrownBy(() -> containers.create(networked())).isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("pending cleanup");
            assertThatThrownBy(() -> containers.start("net-1")).isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("pending cleanup");
        }
    }

    @Test
    void forcedRemovalPersistsIntentAndCleansIndependentResourcesWhenTaskLookupFails() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.failTaskLookup = true;
            assertThatThrownBy(() -> containers.remove("net-1",
                    RemoveOptions.builder().force(true).removeSnapshot(true).build()))
                    .isInstanceOf(ContainerdException.class).hasMessageContaining("shim died");
            var restarted = service(fake, net);
            assertThat(restarted.pendingRemovals()).extracting(io.nanofaas.containerd.Container::id)
                    .containsExactly("net-1");
            assertThat(restarted.pendingRemovals().getFirst().labels())
                    .containsEntry(ContainersServiceImpl.NETWORK_LABEL, "mynet");
            assertThat(fake.events).contains("detach:mynet:-1");
            assertThat(fake.taskExists).isFalse();
            assertThat(fake.stored).isNull();
            assertThat(fake.snapshotExists).isFalse();
            fake.failTaskLookup = false;
            restarted.remove("net-1", RemoveOptions.builder().build());
            assertThat(restarted.pendingRemovals()).isEmpty();
        }
    }

    @Test
    void nonForcedRemovalLeavesResourcesIntactWhenTaskLookupFails() throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            containers.start("net-1");
            fake.events.clear();
            fake.failTaskLookup = true;
            assertThatThrownBy(() -> containers.remove("net-1", RemoveOptions.builder().removeSnapshot(true).build()))
                    .isInstanceOf(ContainerdException.class).hasMessageContaining("shim died");
            assertThat(fake.events).isEmpty();
            assertThat(fake.taskExists).isTrue();
            assertThat(fake.stored).isNotNull();
            assertThat(fake.snapshotExists).isTrue();
            assertThat(containers.pendingRemovals()).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = containerd.v1.types.Status.class,
            names = {"PAUSED", "CREATED"})
    void startDoesNotDestroyAnExistingTask(containerd.v1.types.Status status) throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            fake.taskExists = true;
            fake.taskStatus = status;
            assertThatThrownBy(() -> containers.start("net-1")).isInstanceOf(ContainerStartException.class);
            assertThat(fake.taskExists).isTrue();
            assertThat(fake.taskStatus).isEqualTo(status);
            assertThat(fake.events).isEmpty();
        }
    }

    @Test
    void rejectedTaskCreateDoesNotRollBackTheTaskOwnedByAnotherCaller() throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            fake.rejectTaskCreate = true;
            assertThatThrownBy(() -> containers.start("net-1")).isInstanceOf(ContainerdException.class);
            assertThat(fake.taskExists).isTrue();
            assertThat(fake.taskStatus).isEqualTo(containerd.v1.types.Status.PAUSED);
            assertThat(fake.events).isEmpty();
        }
    }

    @Test
    void failedStartRollsBackTheTaskCreatedByThisCall() throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            fake.failTaskStart = true;
            assertThatThrownBy(() -> containers.start("net-1")).isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("start failed");
            assertThat(fake.taskExists).isFalse();
            assertThat(fake.events).containsSubsequence("task-create", "task-start", "kill:9", "task-delete");
        }
    }

    @Test
    void removalReapsStoppedTaskEvenWhenBothWaitCallsTimeOut() throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            containers.start("net-1");
            fake.deadlineOnWait = true;
            containers.remove("net-1", RemoveOptions.builder().force(true).removeSnapshot(true).build());
            assertThat(fake.events).containsSubsequence("kill:15", "kill:9", "task-delete");
            assertThat(fake.taskExists).isFalse();
            assertThat(fake.stored).isNull();
            assertThat(fake.snapshotExists).isFalse();
            assertThat(containers.pendingRemovals()).isEmpty();
        }
    }

    @Test
    void failedTaskDeletionPreservesMetadataAndSnapshotForRetryAfterRestart() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.deadlineOnWait = true;
            fake.failTaskDelete = true;
            var remove = RemoveOptions.builder().force(true).removeSnapshot(true).build();
            assertThatThrownBy(() -> containers.remove("net-1", remove)).isInstanceOf(ContainerdException.class);
            assertThat(fake.events).contains("detach:mynet:" + PID);
            assertThat(fake.taskStatus).isEqualTo(containerd.v1.types.Status.STOPPED);
            assertThat(fake.taskExists).isTrue();
            assertThat(fake.stored).isNotNull();
            assertThat(fake.snapshotExists).isTrue();
            var restarted = service(fake, net);
            assertThat(restarted.pendingRemovals()).extracting(io.nanofaas.containerd.Container::id).containsExactly("net-1");
            fake.failTaskDelete = false;
            restarted.remove("net-1", remove);
            assertThat(fake.taskExists).isFalse();
            assertThat(fake.stored).isNull();
            assertThat(fake.snapshotExists).isFalse();
            assertThat(restarted.pendingRemovals()).isEmpty();
        }
    }

    @Test
    void missingMetadataDoesNotHideAnOrphanTaskOrClearItsJournal() throws Exception {
        try (var fake = new FakeContainerd()) {
            var containers = service(fake, new RecordingNetwork(fake.events));
            containers.create(networked());
            containers.start("net-1");
            fake.stored = null;
            fake.taskStatus = containerd.v1.types.Status.STOPPED;
            assertThatThrownBy(() -> containers.remove("net-1",
                    RemoveOptions.builder().force(true).removeSnapshot(true).build()))
                    .isInstanceOf(ContainerdException.class);
            assertThat(fake.taskExists).isTrue();
            assertThat(fake.snapshotExists).isTrue();
            assertThat(containers.pendingRemovals()).extracting(io.nanofaas.containerd.Container::id).containsExactly("net-1");
            assertThat(TestServices.tasks(fake.channel).list()).extracting(io.nanofaas.containerd.TaskInfo::containerId)
                    .containsExactly("net-1");
        }
    }
}
