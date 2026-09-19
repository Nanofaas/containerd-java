package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Containers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Container lifecycle on top of containerd's Containers, Tasks and Snapshots services. */
public final class ContainersServiceImpl implements Containers {

    private static final Logger log = LoggerFactory.getLogger(ContainersServiceImpl.class);

    /**
     * Where a container's captured output lives, kept as a label on the container itself. The path
     * is decided at create time but needed again at task create and at every logs() call, and
     * containerd is already storing metadata for this container — holding it here means the caller
     * never has to hand it back, and it survives a client restart.
     */
    static final String LOG_LABEL = "io.nanofaas.containerd/log.path";

    /**
     * The network a container was attached to, kept on the container itself. Detaching happens
     * long after attaching, sometimes from a different process entirely, and asking the caller to
     * remember would mean a crash in between leaks an address nobody can account for.
     */
    static final String NETWORK_LABEL = "io.nanofaas.containerd/cni.network";

    /**
     * The host file bind-mounted over the container's /etc/resolv.conf. It has to be declared at
     * create time, but its contents are only known after the network is attached, which happens
     * once the task is running — so an empty file is mounted and filled in later. That is the same
     * trick docker uses, and the reason a bind mount is used rather than writing into the image:
     * most images have no /etc/resolv.conf at all, and there is no writable rootfs to put one in
     * until the container is already running.
     */
    static final String RESOLV_CONF_LABEL = "io.nanofaas.containerd/dns.resolvconf";

    /**
     * Where per-container files this library owns are kept when the caller names no directory.
     *
     * <p>Under the temporary directory because it is the one place writable by whoever is running,
     * which a default has to be. It is the wrong place for a host that reboots with a running
     * container: the file bind-mounted into that container would be gone while the container still
     * pointed at it. Anything long-lived should set
     * {@link io.nanofaas.containerd.spi.ContainerdClient.Builder#stateDirectory}.
     */
    static final Path DEFAULT_STATE_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "containerd-java-state");

    /** Default grace period between SIGTERM and SIGKILL in {@link #stop(String)}. */
    static final java.time.Duration DEFAULT_STOP_TIMEOUT = java.time.Duration.ofSeconds(10);

    /** How long {@link #close()} waits for in-flight exec IO before abandoning it. */
    static final java.time.Duration IO_SHUTDOWN_TIMEOUT = java.time.Duration.ofSeconds(5);

    /** How long the stdin task gets to finish before it is treated as stuck in {@code open(2)}. */
    private static final java.time.Duration STDIN_GRACE = java.time.Duration.ofSeconds(5);

    private final containerd.services.containers.v1.ContainersGrpc.ContainersBlockingStub stub;
    private final SnapshotManager snapshots;
    private final LeaseManager leases;
    private final ImageRootfsResolver rootfsResolver;
    private final TasksServiceImpl tasks;
    private final String snapshotter;
    private final String runtimeName;
    private final java.time.Duration stopTimeout;
    private final io.nanofaas.containerd.spi.ContainerNetwork network;
    private final Path stateDirectory;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName, String runtimeBinaryName) {
        this(channel, snapshotter, runtimeName, runtimeBinaryName, DEFAULT_STOP_TIMEOUT, null);
    }

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName,
                                 String runtimeBinaryName, java.time.Duration stopTimeout) {
        this(channel, snapshotter, runtimeName, runtimeBinaryName, stopTimeout, null);
    }

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName,
                                 String runtimeBinaryName, java.time.Duration stopTimeout,
                                 io.nanofaas.containerd.spi.ContainerNetwork network) {
        this(channel, snapshotter, runtimeName, runtimeBinaryName, stopTimeout, network, DEFAULT_STATE_DIR);
    }

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName,
                                 String runtimeBinaryName, java.time.Duration stopTimeout,
                                 io.nanofaas.containerd.spi.ContainerNetwork network,
                                 Path stateDirectory) {
        this(channel, snapshotter, runtimeName, runtimeBinaryName, stopTimeout, network, stateDirectory, false);
    }

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName,
                                 String runtimeBinaryName, java.time.Duration stopTimeout,
                                 io.nanofaas.containerd.spi.ContainerNetwork network,
                                 Path stateDirectory, boolean systemdCgroup) {
        this.network = network;
        this.stateDirectory = stateDirectory;
        this.stub = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel);
        this.leases = new LeaseManager(channel);
        this.rootfsResolver = new ImageRootfsResolver(channel);
        this.tasks = new TasksServiceImpl(channel, runtimeBinaryName, systemdCgroup);
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public NetworkAttachment networkAttachment(String id) {
        ProtoMapper.requireValidId(id);
        // An unavailable daemon is not an absent attachment.
        containerOf(id);
        var entry = ContainerJournal.read(stateDirectory, id);
        return entry == null ? null : entry.attachment;
    }

    @Override
    public List<Container> pendingRemovals() {
        return ContainerJournal.list(stateDirectory).stream().filter(entry -> entry.pending)
                .map(entry -> ProtoMapper.map(entry.container)).toList();
    }

    @Override
    public Container create(ContainerSpec spec) {
        ProtoMapper.requireValidId(spec.id());
        refusePendingCleanup(spec.id());
        if (spec.network() != null && network == null) {
            throw new ContainerdException("container " + spec.id() + " asks for network \""
                    + spec.network() + "\" but this client has no ContainerNetwork. Build it with"
                    + " .network(...) — starting the container without one would leave it silently"
                    + " unreachable");
        }
        log.debug("container create start: id={} image={}", spec.id(), spec.image());

        ImageRootfsResolver.ResolvedImage image;
        try {
            image = rootfsResolver.resolve(spec.image());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
        // The snapshot is prepared before the container that will own it exists. A lease owns it
        // in between, so a process killed in that window leaves something containerd will collect
        // when the lease expires rather than a snapshot nobody can account for.
        LeaseManager.Lease lease = leases.create("containerd-java-create-" + spec.id());
        try {
            prepareSnapshotOrThrow(spec.id(), image.chainId(), lease);
            return createContainer(spec, image.config(), lease);
        } finally {
            // Released as soon as the container references the snapshot itself; on the failure
            // path the snapshot is already gone and the lease has nothing left to hold.
            leases.release(lease);
        }
    }

    private Container createContainer(ContainerSpec spec, ImageConfig imageConfig,
                                      LeaseManager.Lease lease) {
        boolean metadataCreated = false;
        try {
            var container = containerd.services.containers.v1.Container.newBuilder()
                    .setId(spec.id())
                    .setImage(spec.image())
                    .setSnapshotter(snapshotter)
                    .setSnapshotKey(spec.id())
                    .setSpec(OciSpecBuilder.buildContainerSpec(spec, imageConfig, networkMounts(spec)))
                    .setRuntime(containerd.services.containers.v1.Container.Runtime.newBuilder()
                            .setName(runtimeName))
                    // User labels first: the GC ref must win, because losing it would let
                    // containerd collect the snapshot out from under a live container.
                    .putAllLabels(spec.labels())
                    .putAllLabels(logLabels(spec))
                    .putAllLabels(networkLabels(spec))
                    .putLabels("containerd.io/gc.ref.snapshot." + snapshotter, spec.id())
                    .build();
            var containers = lease == null ? stub : stub.withInterceptors(lease.asHeader());
            var created = containers.create(containerd.services.containers.v1.CreateContainerRequest.newBuilder()
                    .setContainer(container).build()).getContainer();
            metadataCreated = true;
            new ContainerJournal(created).save(stateDirectory);
            log.debug("container create complete: id={}", spec.id());
            return ProtoMapper.map(created);
        } catch (RuntimeException e) {
            // Every failure after prepare, not just a gRPC one. The snapshot exists and nothing
            // references it yet, so anything that stops the container from being created leaves it
            // owned by no one and never collected — the stale snapshot that prepareSnapshotOrThrow
            // has to work around above. Catching only StatusRuntimeException left that hole open
            // for every other failure: a spec this code cannot build, a log directory it cannot
            // create, anything thrown between the two calls.
            // A failed journal write after Create must leave the daemon-owned snapshot intact.
            // The container remains discoverable through list() and remove() can rebuild its journal.
            if (!metadataCreated) {
                try {
                    snapshots.remove(snapshotter, spec.id());
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (e instanceof StatusRuntimeException status) {
                if (status.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                    throw new ContainerAlreadyExistsException("container " + spec.id() + " already exists", status);
                }
                throw StatusExceptionMapper.map(status, StatusExceptionMapper.ResourceKind.CONTAINER);
            }
            throw e;
        }
    }

    /**
     * The log-path labels for a spec that asks for captured output, empty otherwise. Creating the
     * directory here rather than at task start means a failure to do so is reported by create(),
     * where the caller asked for it.
     */
    private static java.util.Map<String, String> logLabels(ContainerSpec spec) {
        if (spec.logDirectory() == null) {
            return java.util.Map.of();
        }
        try {
            Files.createDirectories(spec.logDirectory());
        } catch (IOException e) {
            throw new ContainerdException("could not create the log directory " + spec.logDirectory(), e);
        }
        return java.util.Map.of(LOG_LABEL, spec.logDirectory().resolve(spec.id() + ".log").toString());
    }

    /** The network label, so detach can find the network long after create decided it. */
    private java.util.Map<String, String> networkLabels(ContainerSpec spec) {
        if (spec.network() == null) {
            return java.util.Map.of();
        }
        return java.util.Map.of(NETWORK_LABEL, spec.network(),
                RESOLV_CONF_LABEL, resolvConfPath(spec.id()).toString());
    }

    private Path resolvConfPath(String containerId) {
        return stateDirectory.resolve(containerId).resolve("resolv.conf");
    }

    /**
     * Creates the empty resolv.conf that will be mounted into the container, and the mount for it.
     *
     * <p>World-readable because the container's process may run as any uid, and it is a file whose
     * whole content this library wrote.
     */
    private List<ContainerSpec.MountSpec> networkMounts(ContainerSpec spec) {
        if (spec.network() == null) {
            return List.of();
        }
        Path resolvConf = resolvConfPath(spec.id());
        try {
            Files.createDirectories(resolvConf.getParent());
            Files.writeString(resolvConf, "");
            resolvConf.toFile().setReadable(true, false);
        } catch (IOException e) {
            throw new ContainerdException("could not prepare " + resolvConf + " for container "
                    + spec.id() + ". This is the client's state directory; point it somewhere"
                    + " writable with ContainerdClient.builder().stateDirectory(...)", e);
        }
        return List.of(new ContainerSpec.MountSpec("/etc/resolv.conf", "bind",
                resolvConf.toString(), List.of("rbind", "ro")));
    }

    /**
     * Prepares the container's snapshot. ALREADY_EXISTS means the id is already taken: if the
     * container exists this is a duplicate create; if it does not, a stale snapshot from a
     * previous partial create is removed and prepare is retried once.
     */
    private void prepareSnapshotOrThrow(String id, String parentChainId, LeaseManager.Lease lease) {
        io.grpc.ClientInterceptor header = lease == null ? null : lease.asHeader();
        try {
            snapshots.prepare(snapshotter, id, parentChainId, header);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.ALREADY_EXISTS) {
                throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.SNAPSHOT);
            }
            if (containerExists(id)) {
                throw new ContainerAlreadyExistsException("container " + id + " already exists", e);
            }
            log.warn("removing stale snapshot {} (no container with that id) and retrying prepare", id);
            try {
                snapshots.remove(snapshotter, id);
                snapshots.prepare(snapshotter, id, parentChainId, header);
            } catch (StatusRuntimeException retryFailure) {
                throw StatusExceptionMapper.map(retryFailure, StatusExceptionMapper.ResourceKind.SNAPSHOT);
            }
        }
    }

    private boolean containerExists(String id) {
        try {
            stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build());
            return true;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return false;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public ContainerStatus inspect(String id) {
        containerd.services.containers.v1.Container container;
        try {
            container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
        ContainerState state = ContainerState.UNKNOWN;
        int pid = -1;
        ExitStatus exitStatus = null;
        var task = tasks.find(id);
        if (task.isPresent()) {
            state = task.get().state();
            pid = task.get().pid();
            if (state == ContainerState.STOPPED) {
                exitStatus = new ExitStatus(task.get().exitCode(), task.get().exitedAt());
            }
        }
        return new ContainerStatus(container.getId(), container.getImage(), state, pid,
                exitStatus, container.getSnapshotKey(), ProtoMapper.map(container).createdAt());
    }

    @Override
    public String logs(String id) {
        String path = containerOf(id).getLabelsMap().get(LOG_LABEL);
        if (path == null) {
            throw new ContainerdException("container " + id + " was not created with a log directory,"
                    + " so containerd discarded its output. Set ContainerSpec.logDirectory(...) before"
                    + " creating it: a task's destination cannot be chosen after it has started");
        }
        return readLog(Path.of(path));
    }

    /** Removes the per-container files this library created, such as the mounted resolv.conf. */
    private void removeStateDirectory(String id) {
        Path dir = stateDirectory.resolve(id);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            for (var entry : entries.filter(path -> !path.getFileName().toString().equals("lifecycle.properties")).toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(dir.resolve("lifecycle.properties"));
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new ContainerdException("could not remove the state directory " + dir, e);
        }
    }

    /** Reads a log file, treating "not there yet" as empty: the shim creates it when it first writes. */
    private static String readLog(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new ContainerdException("could not read the container log " + path, e);
        }
    }

    private containerd.services.containers.v1.Container containerOf(String id) {
        try {
            return stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public List<Container> list() {
        try {
            return stub.list(containerd.services.containers.v1.ListContainersRequest.getDefaultInstance())
                    .getContainersList().stream()
                    .map(ProtoMapper::map)
                    .toList();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public void remove(String id, RemoveOptions options) {
        ProtoMapper.requireValidId(id);
        var entry = journal(id);
        if (entry == null) {
            if (runtimeTask(id) != null) {
                throw new ContainerdException("task " + id + " still exists without container metadata; cleanup cannot be confirmed");
            }
            return;
        }
        // Without force, prove that removal is safe before accepting a durable cleanup intent.
        TaskInfo task = options.force() ? null : taskForRemoval(id);
        if (task != null && task.state() != ContainerState.STOPPED) {
            throw new ContainerdException("container " + id + " is still running; stop it first or use RemoveOptions.force(true)");
        }
        entry.pending = true;
        entry.removeSnapshot |= options.removeSnapshot();
        entry.save(stateDirectory);
        ContainerdException failure = null;
        boolean lookupFailed = false;
        if (options.force()) {
            try { task = taskForRemoval(id); }
            catch (RuntimeException e) {
                lookupFailed = true;
                failure = cleanupFailure(failure, e);
            }
        }
        boolean running = task != null && task.state() != ContainerState.STOPPED;
        try { detachNetwork(id, running ? task.pid() : -1); }
        catch (RuntimeException e) { failure = cleanupFailure(failure, e); }
        boolean taskRemoved = task == null && !lookupFailed;
        if (!taskRemoved) {
            RuntimeException terminationFailure = null;
            try {
                if (running || lookupFailed) terminate(id);
            } catch (TaskNotFoundException ignored) {
                // Get/Kill also depend on container metadata; Delete/List must still confirm removal.
            } catch (RuntimeException e) { terminationFailure = e; }
            // A wait timeout does not prove that the task is still running. Always attempt Delete.
            try {
                try { tasks.delete(id); }
                catch (TaskNotFoundException e) {
                    if (runtimeTask(id) != null) {
                        throw new ContainerdException("task " + id
                                + " still exists in runtime inventory; retain metadata and retry cleanup", e);
                    }
                }
                taskRemoved = true;
            } catch (RuntimeException e) {
                failure = cleanupFailure(failure, e);
                if (terminationFailure != null) failure = cleanupFailure(failure, terminationFailure);
            }
        }
        // Tasks.Get/Delete require container metadata. Removing it before reaping the task makes
        // a STOPPED runtime task unreachable by those RPCs and destroys the retry path.
        if (taskRemoved) {
            try {
                stub.delete(containerd.services.containers.v1.DeleteContainerRequest.newBuilder().setId(id).build());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != io.grpc.Status.Code.NOT_FOUND) failure = cleanupFailure(failure, e);
            }
            if (entry.removeSnapshot && !entry.container.getSnapshotKey().isEmpty()) {
                try { snapshots.remove(entry.container.getSnapshotter(), entry.container.getSnapshotKey()); }
                catch (RuntimeException e) { failure = cleanupFailure(failure, e); }
            }
        }
        if (failure != null) throw failure;
        removeStateDirectory(id);
    }

    private TaskInfo taskForRemoval(String id) {
        return tasks.find(id).orElseGet(() -> runtimeTask(id));
    }

    private TaskInfo runtimeTask(String id) {
        return tasks.list().stream().filter(task -> id.equals(task.containerId())).findFirst().orElse(null);
    }

    private static ContainerdException cleanupFailure(ContainerdException primary, RuntimeException failure) {
        if (primary == null) return failure instanceof ContainerdException typed ? typed
                : new ContainerdException("container cleanup failed: " + failure.getMessage(), failure);
        primary.addSuppressed(failure);
        return primary;
    }

    private ContainerJournal journal(String id) {
        var entry = ContainerJournal.read(stateDirectory, id);
        if (entry != null) return entry;
        try {
            entry = new ContainerJournal(containerOf(id));
            // Legacy containers may already have an allocation without a journal.
            entry.networkPending = entry.container.containsLabels(NETWORK_LABEL);
            return entry;
        } catch (ContainerNotFoundException e) {
            return null;
        }
    }

    private void refusePendingCleanup(String id) {
        var entry = ContainerJournal.read(stateDirectory, id);
        if (entry != null && entry.pending) {
            throw new ContainerdException("container " + id + " has pending cleanup; complete remove before reuse");
        }
    }

    @Override
    public int start(String id) {
        refusePendingCleanup(id);
        TaskInfo existing = tasks.find(id).orElse(null);
        if (existing != null && existing.state() != ContainerState.STOPPED) {
            throw new ContainerStartException("task for container " + id + " already exists in state "
                    + existing.state(), null);
        }
        if (existing != null && existing.state() == ContainerState.STOPPED) {
            log.debug("task for container {} is stopped; deleting before restart", id);
            tasks.delete(id);
        }
        boolean taskCreated = false;
        try {
            tasks.create(id);
            taskCreated = true;
            int pid = tasks.start(id);
            attachNetwork(id, pid);
            return pid;
        } catch (RuntimeException e) {
            try {
                if (taskCreated && tasks.exists(id)) {
                    tasks.kill(id, Signal.KILL);
                    tasks.wait(id, stopTimeout);
                    tasks.delete(id);
                }
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            if (e instanceof ContainerdException mapped) {
                // already a typed library exception (e.g. ContainerNotFoundException from a
                // missing container, ContainerStartException) — do not re-wrap
                throw mapped;
            }
            throw new ContainerStartException("failed to start container " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Attaches the container's network, if it asked for one.
     *
     * <p>A failure here is a failed start: the caller asked for a container on a network and would
     * otherwise be handed a running one that cannot reach anything. Detach runs first, because a
     * plugin that failed part-way may already have taken an address.
     */
    private void attachNetwork(String id, int pid) {
        var entry = journal(id);
        String attachTo = entry == null ? null : entry.container.getLabelsMap().get(NETWORK_LABEL);
        if (attachTo == null) return;
        if (network == null) throw new ContainerdException("no ContainerNetwork configured for " + id);
        entry.networkPending = true;
        entry.save(stateDirectory); // Before ADD: partial ADD must be recoverable too.
        try {
            entry.attachment = network.attach(id, attachTo, pid);
            entry.save(stateDirectory);
            writeResolvConf(id, entry.attachment);
        } catch (RuntimeException e) {
            entry.pending = true;
            try { entry.save(stateDirectory); } catch (RuntimeException secondary) { e.addSuppressed(secondary); }
            try { detachNetwork(id, pid); } catch (RuntimeException secondary) { e.addSuppressed(secondary); }
            throw new ContainerStartException("container " + id + " started but could not be attached"
                    + " to network " + attachTo + ": " + e.getMessage(), e);
        }
    }

    /** DEL is idempotent; keep its identity until both DEL and the journal write succeed. */
    private void detachNetwork(String id, int pid) {
        var entry = journal(id);
        if (entry == null || !entry.networkPending) return;
        String detachFrom = entry.container.getLabelsMap().get(NETWORK_LABEL);
        try {
            if (network == null) throw new ContainerdException("no ContainerNetwork configured for " + id);
            network.detach(id, detachFrom, pid);
            entry.networkPending = false;
            entry.attachment = null;
            entry.save(stateDirectory);
        } catch (RuntimeException e) {
            entry.pending = true;
            try { entry.save(stateDirectory); } catch (RuntimeException secondary) { e.addSuppressed(secondary); }
            throw cleanupFailure(null, e);
        }
    }

    /**
     * Writes the network's DNS into the file mounted at the container's /etc/resolv.conf.
     *
     * <p>CNI reports DNS but does not apply it — that is the runtime's job, and without this a
     * container has an address and a route and still cannot resolve a single name, which is a more
     * confusing kind of broken than having no network at all.
     */
    private void writeResolvConf(String id, io.nanofaas.containerd.NetworkAttachment attachment) {
        if (attachment == null) {
            return;
        }
        String contents = attachment.toResolvConf();
        if (contents.isEmpty()) {
            log.debug("network for {} reported no DNS; leaving its resolv.conf empty", id);
            return;
        }
        String path = containerOf(id).getLabelsMap().get(RESOLV_CONF_LABEL);
        if (path == null) {
            return;
        }
        try {
            Files.writeString(Path.of(path), contents);
        } catch (IOException e) {
            throw new ContainerdException("could not write the DNS configuration for " + id
                    + " to " + path, e);
        }
    }

    @Override
    public Optional<ExitStatus> stop(String id) {
        var task = tasks.find(id).orElse(null);
        ContainerdException failure = null;
        try { detachNetwork(id, task == null ? -1 : task.pid()); }
        catch (RuntimeException e) { failure = cleanupFailure(failure, e); }
        Optional<ExitStatus> result = Optional.empty();
        if (task != null) {
            try {
                result = Optional.of(terminate(id));
                deleteTaskQuietly(id);
            } catch (TaskNotFoundException ignored) {
                deleteTaskQuietly(id);
            } catch (RuntimeException e) {
                var primary = e instanceof ContainerStopException ? e
                        : new ContainerStopException("failed to stop container " + id + ": " + e.getMessage(), e);
                if (failure != null) primary.addSuppressed(failure);
                throw primary;
            }
        }
        if (failure != null) throw failure;
        return result;
    }

    /**
     * SIGTERM, wait for the grace period, then SIGKILL and wait again.
     *
     * <p>Both waits are bounded. SIGKILL cannot be caught, but it does not reach a process parked
     * in uninterruptible sleep — a wedged NFS or fuse mount is the usual cause — and such a task
     * never reaps. An unbounded wait there blocks the caller for the life of the process, so the
     * second grace period is spent and then the stop is reported as failed.
     */
    private ExitStatus terminate(String id) {
        tasks.kill(id, Signal.TERM);
        try {
            return tasks.wait(id, stopTimeout);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                throw e;
            }
        }
        log.debug("stop: container {} did not exit within {}, sending SIGKILL", id, stopTimeout);
        tasks.kill(id, Signal.KILL);
        try {
            return tasks.wait(id, stopTimeout);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                throw e;
            }
            throw new ContainerStopException("container " + id + " did not exit within " + stopTimeout
                    + " of SIGKILL; the task is most likely stuck in uninterruptible sleep."
                    + " Its state is left intact for inspection", e);
        }
    }

    /** Deletes the (now exited) task. A task already reaped by containerd is not an error. */
    private void deleteTaskQuietly(String id) {
        try {
            tasks.delete(id);
        } catch (TaskNotFoundException e) {
            // already gone
        }
    }

    @Override
    public void kill(String id, Signal signal) {
        tasks.kill(id, signal);
    }

    @Override
    public ExitStatus wait(String id) {
        return tasks.wait(id);
    }

    @Override
    public ExecResult exec(String id, List<String> command) {
        return exec(id, ExecSpec.builder().command(command).build());
    }

    @Override
    public ExecResult exec(String id, ExecSpec spec) {
        var task = tasks.find(id).orElseThrow(() -> new ExecException(
                "no task for container " + id + "; start the container before exec"));
        if (task.state() != ContainerState.RUNNING) {
            throw new ExecException("task for container " + id + " is not running (state=" + task.state() + ")");
        }
        // The environment the exec'd process should see is the one the container runs with, which
        // containerd already stores as part of the container's spec — no need to resolve the image
        // again. Without it nothing the image ships is on PATH, and its variables are all missing.
        StoredSpec container = StoredSpec.parse(containerSpecOf(id));

        String execId = "exec-" + UUID.randomUUID();
        var fifos = IoManager.createFifoSet(id + "-" + execId);
        // Open the read ends BEFORE the Exec RPC: open(2) blocks until the shim opens its write
        // end (which happens as the process spawns), so a process that exits immediately cannot
        // win the race and leave us with output we never read.
        var stdoutFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stdout()));
        var stderrFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stderr()));
        java.util.concurrent.Future<?> stdinFuture = null;
        try {
            tasks.exec(id, execId, spec, fifos, container);
            int pid = tasks.startExec(id, execId);
            // Always send stdin, even with nothing to write: a process that reads stdin needs
            // EOF to get going, and skipping this (as this did when ExecSpec.stdin() was null)
            // leaves it blocked forever.
            byte[] stdin = spec.stdin() == null
                    ? new byte[0] : spec.stdin().getBytes(StandardCharsets.UTF_8);
            stdinFuture = ioExecutor.submit(() -> {
                try {
                    IoManager.writeFifo(fifos.stdin(), stdin);
                } finally {
                    // Closing our write end is NOT what gives the process EOF: the shim opens the
                    // FIFO read-write and holds a write end of its own, so the pipe stays open
                    // whatever the client does. Only CloseIO closes it. Without this an exec of
                    // anything that reads to EOF hangs here, and so does this thread's reader,
                    // because the process never exits and stdout never ends. In a finally because
                    // a failed write still has to be followed by EOF: otherwise the failure is a
                    // hang rather than an error, which is far harder to read.
                    tasks.closeStdin(id, execId);
                }
            });
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            ExitStatus status = tasks.waitExec(id, execId);
            log.debug("exec complete: containerId={} execId={} pid={} exitCode={}", id, execId, pid, status.code());
            return new ExecResult(status.code(), stdout, stderr);
        } catch (ContainerdException e) {
            throw new ExecException("exec failed for container " + id + ": " + e.getMessage(), e);
        } catch (ExecutionException e) {
            throw new ExecException("exec IO failed for container " + id, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecException("exec interrupted for container " + id, e);
        } finally {
            // If a reader never reached EOF (exec/start failed before the shim opened the FIFO
            // write ends), it is still blocked in open(2): pair it with a write end so it
            // completes. A reader that already hit EOF is left alone — opening a write end with
            // no reader to pair with would block.
            if (!stdoutFuture.isDone()) {
                unblockReader(fifos.stdout());
            }
            if (!stderrFuture.isDone()) {
                unblockReader(fifos.stderr());
            }
            // Mirror image: a writer still blocked in open(2) needs a read end to pair with.
            // "Not finished" is not the same thing as "blocked in open", though, and the two must
            // not be confused here: pairing a read end with a writer that has already closed
            // blocks this thread for good, because nothing will ever open the other side. So give
            // the task a moment to finish on its own, and only treat it as stuck if it does not.
            if (stdinFuture != null && !awaitStdin(id, execId, stdinFuture)) {
                unblockWriter(fifos.stdin());
            }
            try {
                tasks.deleteExec(id, execId);
            } catch (RuntimeException e) {
                log.warn("failed to delete exec process {} for container {}", execId, id, e);
            }
            IoManager.cleanup(fifos);
        }
    }

    /**
     * Waits a short while for the stdin task to finish.
     *
     * @return {@code true} if it finished (however it finished), {@code false} if it is still
     *         running, which at this point means it is blocked in {@code open(2)} waiting for a
     *         reader that is never coming
     */
    private boolean awaitStdin(String id, String execId, java.util.concurrent.Future<?> stdinFuture) {
        try {
            stdinFuture.get(STDIN_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            // Nothing else waits on this future, so a failed write would otherwise vanish. It does
            // not fail the exec — the process ran — but it explains truncated input, which the
            // caller has no other way to find out about.
            log.warn("writing stdin failed for exec {} of container {}; the process ran but may"
                    + " have seen less input than was sent", execId, id, e.getCause());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** Returns the OCI spec containerd stores on the container, or null if it cannot be read. */
    private com.google.protobuf.Any containerSpecOf(String id) {
        try {
            return stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer().getSpec();
        } catch (StatusRuntimeException e) {
            log.debug("could not read the stored spec for {}; exec runs without its environment", id, e);
            return null;
        }
    }

    /** Opens and immediately closes a FIFO's write end so a reader blocked in open(2) proceeds to EOF. */
    private void unblockReader(Path fifo) {
        try (var out = Files.newOutputStream(fifo)) {
            // open-write-close: the paired reader now sees EOF and returns.
        } catch (IOException ignored) {
            // best effort — cleanup() removes the FIFO right after
        }
    }

    /** Opens and immediately closes a FIFO's read end so a writer blocked in open(2) proceeds. */
    private void unblockWriter(Path fifo) {
        try (var in = Files.newInputStream(fifo)) {
            // open-close: the paired writer's open(2) now returns.
        } catch (IOException ignored) {
            // best effort — cleanup() removes the FIFO right after
        }
    }

    /**
     * Shuts the IO virtual-thread pool down and gives in-flight exec IO a moment to drain.
     * The caller closes the gRPC channel right after, so without this an exec running
     * concurrently with close() would have its output truncated with no diagnostic.
     */
    public void close() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(IO_SHUTDOWN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.warn("exec IO still in flight after {}; abandoning it", IO_SHUTDOWN_TIMEOUT);
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }
}
