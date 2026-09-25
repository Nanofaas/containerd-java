package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Version;
import io.nanofaas.containerd.internal.DefaultContainerdClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * A client for the containerd gRPC API.
 *
 * <p>Thread-safe. Owns a single shared gRPC channel; close it when done. All service facades
 * returned by this client operate on the namespace configured at build time.
 */
public interface ContainerdClient extends AutoCloseable {

    /** {@return containerd's version and revision; doubles as a health check} */
    Version version();

    /** {@return the image operations facade: pull, get, list, remove} */
    Images images();

    /** {@return the container lifecycle facade: create, inspect, list, remove, start, stop, kill, wait, exec} */
    Containers containers();

    /** {@return the event stream facade} */
    Events events();

    /** {@return the containerd namespace every call through this client is scoped to} */
    String namespace();

    /** {@return the snapshotter used for container root filesystems} */
    String snapshotter();

    /** {@return the runtime identifier passed to tasks, e.g. {@code io.containerd.runc.v2}} */
    String runtimeName();

    /**
     * Releases the gRPC channel and every executor this client owns. Open event subscriptions
     * are cancelled. The client cannot be used afterwards.
     */
    @Override
    void close();

    /** {@return a builder for a new client} */
    static Builder builder() {
        return new Builder();
    }

    /** Configures and creates a {@link ContainerdClient}. */
    final class Builder {

        // containerd's documented default, and the whole point of this field is that socketPath()
        // overrides it.
        @SuppressWarnings("java:S1075")
        private String socketPath = "/run/containerd/containerd.sock";
        private String namespace = "nanofaas";
        private String snapshotter = "overlayfs";
        private String runtimeName = "io.containerd.runc.v2";
        private String runtimeBinaryName;
        private boolean systemdCgroup;
        private Duration stopTimeout = Duration.ofSeconds(10);
        private ContainerNetwork network;
        // Null means "the client's default": the default belongs with the code that uses it, not
        // spelled out a second time here where the two could drift.
        private Path stateDirectory;

        private Builder() {
        }

        /**
         * Sets the Unix domain socket to connect to.
         *
         * @param socketPath socket path; defaults to {@code /run/containerd/containerd.sock}
         * @return this builder
         */
        public Builder socketPath(String socketPath) {
            this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
            return this;
        }

        /**
         * Sets the containerd namespace every call is scoped to.
         *
         * @param namespace namespace name; defaults to {@code nanofaas}
         * @return this builder
         */
        public Builder namespace(String namespace) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            return this;
        }

        /**
         * Sets the snapshotter for container root filesystems.
         *
         * @param snapshotter snapshotter name; defaults to {@code overlayfs}
         * @return this builder
         */
        public Builder snapshotter(String snapshotter) {
            this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
            return this;
        }

        /**
         * Sets the runtime identifier passed to tasks.
         *
         * @param runtimeName runtime id; defaults to {@code io.containerd.runc.v2}
         * @return this builder
         */
        public Builder runtimeName(String runtimeName) {
            this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
            return this;
        }

        /**
         * When set (e.g. {@code crun}), the runc-v2 shim is told to exec this OCI runtime binary
         * instead of its default. Requires a shim that supports the {@code binary_name} option.
         *
         * @param runtimeBinaryName OCI runtime binary, or {@code null} for the shim's default
         * @return this builder
         */
        public Builder runtimeBinaryName(String runtimeBinaryName) {
            this.runtimeBinaryName = runtimeBinaryName;
            return this;
        }

        /**
         * Configures the runc-v2 shim to use systemd cgroup management.
         * @param enabled true for delegated systemd cgroups; false by default
         * @return this builder
         */
        public Builder systemdCgroup(boolean enabled) {
            this.systemdCgroup = enabled;
            return this;
        }

        /**
         * How long {@link Containers#stop} waits after SIGTERM before sending SIGKILL.
         * Default 10 seconds.
         *
         * @param stopTimeout grace period; must be positive
         * @return this builder
         */
        public Builder stopTimeout(Duration stopTimeout) {
            Objects.requireNonNull(stopTimeout, "stopTimeout");
            if (stopTimeout.isNegative() || stopTimeout.isZero()) {
                throw new IllegalArgumentException("stopTimeout must be positive, got: " + stopTimeout);
            }
            this.stopTimeout = stopTimeout;
            return this;
        }

        /**
         * Attaches containers that ask for one to their network.
         *
         * <p>Left unset, {@link io.nanofaas.containerd.ContainerSpec.Builder#network} is refused
         * at create time rather than ignored: a container that asked to be on a network and
         * silently is not is worse than one that never started.
         *
         * @param network the networking implementation, for instance the CNI one from
         *        {@code containerd-java-cni}
         * @return this builder
         */
        public Builder network(ContainerNetwork network) {
            this.network = Objects.requireNonNull(network, "network");
            return this;
        }

        /**
         * Where per-container files this library owns are kept.
         *
         * <p>This includes the lifecycle journal and the {@code resolv.conf} bind-mounted into a
         * networked container. State is isolated by normalized absolute socket path and namespace.
         * Use the same socket path, namespace and directory after restart. It has
         * to outlive nothing less than the container itself: the mount points at this file, so if
         * it disappears while the container runs — a reboot clearing the default temporary
         * directory would do it — the container is left with a mount pointing at nothing.
         *
         * <p>Defaults to a directory under {@code java.io.tmpdir}, which is writable by whoever is
         * running and survives nothing. Anything long-lived should name a persistent path.
         *
         * @param stateDirectory directory for this client's per-container files; created when
         *        first needed by a lifecycle operation rather than at client construction
         * @return this builder
         */
        public Builder stateDirectory(Path stateDirectory) {
            this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
            return this;
        }

        /** {@return a client connected to the configured socket} */
        public ContainerdClient build() {
            return new DefaultContainerdClient(socketPath, namespace, snapshotter, runtimeName,
                    runtimeBinaryName, stopTimeout, network, stateDirectory, systemdCgroup);
        }
    }
}
