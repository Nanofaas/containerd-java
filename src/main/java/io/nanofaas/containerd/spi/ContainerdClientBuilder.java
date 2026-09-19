package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.internal.DefaultContainerdClient;

import java.util.Objects;

/** Package-private: callers reach this through {@link ContainerdClient#builder()}. */
final class ContainerdClientBuilder implements ContainerdClient.Builder {

    // containerd's documented default, and the whole point of this field is that socketPath()
    // overrides it.
    @SuppressWarnings("java:S1075")
    private String socketPath = "/run/containerd/containerd.sock";
    private String namespace = "nanofaas";
    private String snapshotter = "overlayfs";
    private String runtimeName = "io.containerd.runc.v2";
    private String runtimeBinaryName;
    private boolean systemdCgroup;
    private java.time.Duration stopTimeout = java.time.Duration.ofSeconds(10);
    private ContainerNetwork network;
    // Null means "the client's default": the default belongs with the code that uses it, not
    // spelled out a second time here where the two could drift.
    private java.nio.file.Path stateDirectory;

    @Override
    public ContainerdClient.Builder socketPath(String socketPath) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
        return this;
    }

    @Override
    public ContainerdClient.Builder namespace(String namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        return this;
    }

    @Override
    public ContainerdClient.Builder snapshotter(String snapshotter) {
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
        return this;
    }

    @Override
    public ContainerdClient.Builder runtimeName(String runtimeName) {
        this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
        return this;
    }

    @Override
    public ContainerdClient.Builder runtimeBinaryName(String runtimeBinaryName) {
        this.runtimeBinaryName = runtimeBinaryName;
        return this;
    }

    @Override
    public ContainerdClient.Builder systemdCgroup(boolean enabled) {
        this.systemdCgroup = enabled;
        return this;
    }

    @Override
    public ContainerdClient.Builder stopTimeout(java.time.Duration stopTimeout) {
        Objects.requireNonNull(stopTimeout, "stopTimeout");
        if (stopTimeout.isNegative() || stopTimeout.isZero()) {
            throw new IllegalArgumentException("stopTimeout must be positive, got: " + stopTimeout);
        }
        this.stopTimeout = stopTimeout;
        return this;
    }

    @Override
    public ContainerdClient.Builder network(ContainerNetwork network) {
        this.network = Objects.requireNonNull(network, "network");
        return this;
    }

    @Override
    public ContainerdClient.Builder stateDirectory(java.nio.file.Path stateDirectory) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
        return this;
    }

    @Override
    public ContainerdClient build() {
        return new DefaultContainerdClient(socketPath, namespace, snapshotter, runtimeName,
                runtimeBinaryName, stopTimeout, network, stateDirectory, systemdCgroup);
    }
}
