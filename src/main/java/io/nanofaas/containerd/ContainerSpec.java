package io.nanofaas.containerd;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Desired state of a container to create. Build with {@link #builder()}. */
public final class ContainerSpec {

    /** RLIMIT_NOFILE applied when the caller sets none. */
    public static final long DEFAULT_OPEN_FILES_LIMIT = 1024;

    /**
     * An extra mount added to the container on top of the standard set.
     *
     * @param destination path inside the container
     * @param type filesystem type, for example {@code bind} or {@code tmpfs}
     * @param source path on the host, or the filesystem name for virtual filesystems
     * @param options mount options; {@code null} is treated as none
     */
    public record MountSpec(String destination, String type, String source, List<String> options) {
        /** Defensively copies the options, treating null as none. */
        public MountSpec {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    private final String id;
    private final String image;
    private final List<String> command;
    private final Map<String, String> environment;
    private final String workingDir;
    private final String hostname;
    private final String user;
    private final boolean readonlyRootfs;
    private final boolean hostNetwork;
    private final List<MountSpec> mounts;
    private final String cpuSetCpus;
    private final String cgroupsPath;
    private final long memoryReservationBytes;
    private final long cpuShares;
    private final long cpuQuotaMicros;
    private final long cpuPeriodMicros;
    private final long memoryLimitBytes;
    private final long memorySwapLimitBytes;
    private final long pidsLimit;
    private final long openFilesLimit;
    private final Path logDirectory;
    private final String network;
    private final Map<String, String> labels;

    private ContainerSpec(Builder b) {
        this.id = b.id;
        this.image = b.image;
        this.command = List.copyOf(b.command);
        this.environment = Map.copyOf(b.environment);
        this.workingDir = b.workingDir;
        this.hostname = b.hostname;
        this.user = b.user;
        this.readonlyRootfs = b.readonlyRootfs;
        this.hostNetwork = b.hostNetwork;
        this.mounts = List.copyOf(b.mounts);
        this.cpuSetCpus = b.cpuSetCpus;
        this.cgroupsPath = b.cgroupsPath;
        this.memoryReservationBytes = b.memoryReservationBytes;
        this.cpuShares = b.cpuShares;
        this.cpuQuotaMicros = b.cpuQuotaMicros;
        this.cpuPeriodMicros = b.cpuPeriodMicros;
        this.memoryLimitBytes = b.memoryLimitBytes;
        this.memorySwapLimitBytes = b.memorySwapLimitBytes;
        this.pidsLimit = b.pidsLimit;
        this.openFilesLimit = b.openFilesLimit;
        this.logDirectory = b.logDirectory;
        this.network = b.network;
        this.labels = Map.copyOf(b.labels);
    }

    /** {@return a builder for a new container spec} */
    public static Builder builder() {
        return new Builder();
    }

    /** {@return the container id} */
    public String id() { return id; }
    /** {@return the image reference the root filesystem comes from} */
    public String image() { return image; }
    /** {@return the argv of the init process; {@code /bin/sh} when empty} */
    public List<String> command() { return command; }
    /** {@return the environment variables added on top of a default {@code PATH} and {@code TERM}} */
    public Map<String, String> environment() { return environment; }
    /** {@return the working directory of the init process; {@code /} when unset} */
    public String workingDir() { return workingDir; }
    /** {@return the hostname inside the container; the container id when unset} */
    public String hostname() { return hostname; }
    /** {@return the user as {@code "uid:gid"}. A bare username cannot be honoured — the OCI
     *         runtime spec has no field for one — and leaves the process running as uid 0} */
    public String user() { return user; }
    /** {@return whether the root filesystem is mounted read-only} */
    public boolean readonlyRootfs() { return readonlyRootfs; }
    /** {@return whether the container shares the host's network stack instead of getting its own} */
    public boolean hostNetwork() { return hostNetwork; }
    /** {@return the extra mounts added on top of the standard set (/proc, /dev, /sys, ...)} */
    public List<MountSpec> mounts() { return mounts; }
    /** {@return the relative CPU weight against other containers; 0 leaves it unset} */
    public long cpuShares() { return cpuShares; }
    /** {@return the CFS quota, microseconds of CPU time per period; 0 leaves it unset} */
    public long cpuQuotaMicros() { return cpuQuotaMicros; }
    /** {@return the CFS period the quota is measured over, in microseconds; 0 leaves it unset} */
    public long cpuPeriodMicros() { return cpuPeriodMicros; }
    /** {@return the hard memory limit in bytes; 0 leaves it unset} */
    public long memoryLimitBytes() { return memoryLimitBytes; }
    /** {@return the combined memory + swap limit in bytes; 0 leaves it unset} */
    public long memorySwapLimitBytes() { return memorySwapLimitBytes; }
    /** {@return the allowed CPU list, or null to inherit it} */
    public String cpuSetCpus() { return cpuSetCpus; }
    /** {@return the OCI cgroup path, or null for the runtime default} */
    public String cgroupsPath() { return cgroupsPath; }
    /** {@return the memory reservation in bytes; 0 leaves it unset} */
    public long memoryReservationBytes() { return memoryReservationBytes; }

    /** {@return the maximum number of processes in the container; 0 leaves it unset} */
    public long pidsLimit() { return pidsLimit; }
    /** {@return the RLIMIT_NOFILE the container's process runs with} */
    public long openFilesLimit() { return openFilesLimit; }
    /** {@return where the task's output is written, or {@code null} when it is discarded} */
    public Path logDirectory() { return logDirectory; }
    /** {@return the network to attach the container to, or {@code null} for no networking} */
    public String network() { return network; }
    /** {@return the labels stored on the container alongside containerd's own} */
    public Map<String, String> labels() { return labels; }

    /** Collects the fields of a {@link ContainerSpec}. */
    public static final class Builder {

        /** Creates an empty builder; prefer {@link ContainerSpec#builder()}. */
        public Builder() {
            // every field has its default in its declaration; nothing to do here
        }
        private String id;
        private String image;
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();
        private String workingDir;
        private String hostname;
        private String user;
        private boolean readonlyRootfs;
        private boolean hostNetwork;
        private List<MountSpec> mounts = List.of();
        private String cpuSetCpus;
        private String cgroupsPath;
        private long memoryReservationBytes;
        private long cpuShares;
        private long cpuQuotaMicros;
        private long cpuPeriodMicros;
        private long memoryLimitBytes;
        private long memorySwapLimitBytes;
        private long pidsLimit;
        private long openFilesLimit = DEFAULT_OPEN_FILES_LIMIT;
        private Path logDirectory;
        private String network;
        private Map<String, String> labels = Map.of();

        /**
         * Sets the container id.
         *
         * @param id container id; must satisfy {@link Identifiers}
         * @return this builder
         */
        public Builder id(String id) { this.id = id; return this; }
        /**
         * Sets the image reference the root filesystem comes from.
         *
         * @param image image reference the root filesystem comes from
         * @return this builder
         */
        public Builder image(String image) { this.image = image; return this; }
        /**
         * Sets the argv of the init process.
         *
         * @param command argv of the init process; {@code /bin/sh} when empty
         * @return this builder
         */
        public Builder command(List<String> command) { this.command = Objects.requireNonNull(command, "command"); return this; }
        /**
         * Sets the environment variables added on top of a default {@code PATH} and {@code TERM}.
         *
         * @param environment environment variables added on top of a default {@code PATH} and {@code TERM}
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) { this.environment = Objects.requireNonNull(environment, "environment"); return this; }
        /**
         * Sets the working directory of the init process.
         *
         * @param workingDir working directory of the init process; {@code /} when unset
         * @return this builder
         */
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        /**
         * Sets the hostname inside the container.
         *
         * @param hostname hostname inside the container; the container id when unset
         * @return this builder
         */
        public Builder hostname(String hostname) { this.hostname = hostname; return this; }
        /**
         * Sets the user as {@code "uid:gid"}.
         *
         * @param user user as {@code "uid:gid"}. A bare username cannot be honoured — the OCI
     *         runtime spec has no field for one — and leaves the process running as uid 0
         * @return this builder
         */
        public Builder user(String user) { this.user = user; return this; }
        /**
         * Sets the whether the root filesystem is mounted read-only.
         *
         * @param readonlyRootfs whether the root filesystem is mounted read-only
         * @return this builder
         */
        public Builder readonlyRootfs(boolean readonlyRootfs) { this.readonlyRootfs = readonlyRootfs; return this; }

        /**
         * Shares the host's network stack with the container instead of giving it a private one.
         *
         * <p>By default a container gets a fresh network namespace, which this library leaves
         * empty: no addresses, no routes, no DNS, and nothing reachable from outside. Configuring
         * one needs CNI, which this library does not do. Host networking is the way to run a
         * container that has to reach the network or be reached on a port, at the cost of no
         * network isolation — the container binds host ports directly, and can conflict with
         * anything already listening.
         *
         * @param hostNetwork true to drop the network namespace and use the host's
         * @return this builder
         */
        public Builder hostNetwork(boolean hostNetwork) { this.hostNetwork = hostNetwork; return this; }
        /**
         * Sets the extra mounts added on top of the standard set (/proc, /dev, /sys, .
         *
         * @param mounts extra mounts added on top of the standard set (/proc, /dev, /sys, ...)
         * @return this builder
         */
        public Builder mounts(List<MountSpec> mounts) { this.mounts = Objects.requireNonNull(mounts, "mounts"); return this; }
        /**
         * Sets the relative CPU weight against other containers.
         *
         * @param cpuShares relative CPU weight against other containers; 0 leaves it unset
         * @return this builder
         */
        public Builder cpuShares(long cpuShares) { this.cpuShares = cpuShares; return this; }

        /**
         * Sets the CPU affinity list understood by the OCI runtime.
         * @param cpus Linux CPU list, such as 0-2,4; null leaves it unset
         * @return this builder
         */
        public Builder cpuSetCpus(String cpus) { this.cpuSetCpus = cpus; return this; }

        /**
         * Sets the cgroup path; with systemd use slice:prefix:name.
         * @param path OCI cgroup path, or null for the runtime default
         * @return this builder
         */
        public Builder cgroupsPath(String path) { this.cgroupsPath = path; return this; }

        /**
         * Sets the memory reservation (memory.low on cgroup v2).
         * @param bytes reservation in bytes; 0 leaves it unset
         * @return this builder
         */
        public Builder memoryReservationBytes(long bytes) { this.memoryReservationBytes = bytes; return this; }

        /**
         * Sets the CFS quota: microseconds of CPU time per period.
         *
         * @param cpuQuotaMicros CFS quota: microseconds of CPU time per period; 0 leaves it unset
         * @return this builder
         */
        public Builder cpuQuotaMicros(long cpuQuotaMicros) { this.cpuQuotaMicros = cpuQuotaMicros; return this; }
        /**
         * Sets the CFS period the quota is measured over, in microseconds.
         *
         * @param cpuPeriodMicros CFS period the quota is measured over, in microseconds; 0 leaves it unset
         * @return this builder
         */
        public Builder cpuPeriodMicros(long cpuPeriodMicros) { this.cpuPeriodMicros = cpuPeriodMicros; return this; }
        /**
         * Sets the hard memory limit in bytes.
         *
         * @param memoryLimitBytes hard memory limit in bytes; 0 leaves it unset
         * @return this builder
         */
        public Builder memoryLimitBytes(long memoryLimitBytes) { this.memoryLimitBytes = memoryLimitBytes; return this; }
        /**
         * Sets the combined memory + swap limit in bytes.
         *
         * @param memorySwapLimitBytes combined memory + swap limit in bytes; 0 leaves it unset
         * @return this builder
         */
        public Builder memorySwapLimitBytes(long memorySwapLimitBytes) { this.memorySwapLimitBytes = memorySwapLimitBytes; return this; }
        /**
         * Sets the maximum number of processes in the container.
         *
         * @param pidsLimit maximum number of processes in the container; 0 leaves it unset
         * @return this builder
         */
        public Builder pidsLimit(long pidsLimit) { this.pidsLimit = pidsLimit; return this; }

        /**
         * Sets RLIMIT_NOFILE, the number of file descriptors the container's process may open.
         *
         * <p>The default of {@value #DEFAULT_OPEN_FILES_LIMIT} suits ordinary processes and is far
         * too low for servers that pool connections or memory-map many files: Elasticsearch, for
         * one, refuses to start below 65535 and says so only in output this library does not
         * capture.
         *
         * @param openFilesLimit maximum open file descriptors, applied as both the soft and the
         *        hard limit; must be positive
         * @return this builder
         */
        public Builder openFilesLimit(long openFilesLimit) {
            if (openFilesLimit <= 0) {
                throw new IllegalArgumentException("openFilesLimit must be positive, got: " + openFilesLimit);
            }
            this.openFilesLimit = openFilesLimit;
            return this;
        }

        /**
         * Captures the task's output into this directory, as {@code <id>.log}.
         *
         * <p>containerd throws a task's output away unless told where to put it, and it has to be
         * told before the task starts — there is no attaching to a container after the fact. Left
         * unset, {@link io.nanofaas.containerd.spi.Containers#logs} has nothing to return and a
         * container that dies on startup does so without saying why.
         *
         * <p>stdout and stderr land in the same file, interleaved: containerd sends both to a file
         * destination and ignores a second one.
         *
         * @param logDirectory directory for the log files; created if it does not exist
         * @return this builder
         */
        public Builder logDirectory(Path logDirectory) {
            this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory");
            return this;
        }

        /**
         * Attaches the container to this network once its task starts.
         *
         * <p>Requires the client to have been given a
         * {@link io.nanofaas.containerd.spi.ContainerNetwork}; without one there is nothing to
         * carry the name out to, and {@code create} says so rather than starting a container that
         * silently has no network.
         *
         * @param network network name, as the networking implementation understands it — for CNI,
         *        the {@code name} of a config in the plugin configuration directory
         * @return this builder
         */
        public Builder network(String network) {
            this.network = Objects.requireNonNull(network, "network");
            return this;
        }
        /**
         * Sets the labels stored on the container alongside containerd's own.
         *
         * @param labels labels stored on the container alongside containerd's own
         * @return this builder
         */
        public Builder labels(Map<String, String> labels) { this.labels = Objects.requireNonNull(labels, "labels"); return this; }

        /**
         * Validates the collected fields and creates the spec.
         *
         * @return the container spec
         * @throws IllegalArgumentException if the id or image is missing, or the id is invalid
         */
        public ContainerSpec build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            Identifiers.requireValid(id);
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("image is required");
            }
            if (network != null && hostNetwork) {
                throw new IllegalArgumentException("hostNetwork and network are mutually exclusive:"
                        + " a container sharing the host's network stack has no namespace of its own"
                        + " for a network to be configured in");
            }
            return new ContainerSpec(this);
        }
    }
}
