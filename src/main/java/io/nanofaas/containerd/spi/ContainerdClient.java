package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Version;

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

    /** {@return the low-level task facade, the NanoFaaS fast path} */
    Tasks tasks();

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
        return new ContainerdClientBuilder();
    }

    /** Configures and creates a {@link ContainerdClient}. */
    interface Builder {

        /**
         * Sets the Unix domain socket to connect to.
         *
         * @param socketPath socket path; defaults to {@code /run/containerd/containerd.sock}
         * @return this builder
         */
        Builder socketPath(String socketPath);

        /**
         * Sets the containerd namespace every call is scoped to.
         *
         * @param namespace namespace name; defaults to {@code nanofaas}
         * @return this builder
         */
        Builder namespace(String namespace);

        /**
         * Sets the snapshotter for container root filesystems.
         *
         * @param snapshotter snapshotter name; defaults to {@code overlayfs}
         * @return this builder
         */
        Builder snapshotter(String snapshotter);

        /**
         * Sets the runtime identifier passed to tasks.
         *
         * @param runtimeName runtime id; defaults to {@code io.containerd.runc.v2}
         * @return this builder
         */
        Builder runtimeName(String runtimeName);

        /**
         * When set (e.g. {@code crun}), the runc-v2 shim is told to exec this OCI runtime binary
         * instead of its default. Requires a shim that supports the {@code binary_name} option.
         *
         * @param runtimeBinaryName OCI runtime binary, or {@code null} for the shim's default
         * @return this builder
         */
        Builder runtimeBinaryName(String runtimeBinaryName);

        /**
         * Configures the runc-v2 shim to use systemd cgroup management.
         * @param enabled true for delegated systemd cgroups; false by default
         * @return this builder
         */
        Builder systemdCgroup(boolean enabled);

        /**
         * How long {@link Containers#stop} waits after SIGTERM before sending SIGKILL.
         * Default 10 seconds.
         *
         * @param stopTimeout grace period; must be positive
         * @return this builder
         */
        Builder stopTimeout(java.time.Duration stopTimeout);

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
        Builder network(ContainerNetwork network);

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
        Builder stateDirectory(java.nio.file.Path stateDirectory);

        /** {@return a client connected to the configured socket} */
        ContainerdClient build();
    }
}
