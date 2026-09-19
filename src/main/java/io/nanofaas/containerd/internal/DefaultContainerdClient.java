package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.nanofaas.containerd.Version;
import io.nanofaas.containerd.spi.Containers;
import io.nanofaas.containerd.spi.ContainerdClient;
import io.nanofaas.containerd.spi.Events;
import io.nanofaas.containerd.spi.Images;
import io.nanofaas.containerd.spi.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@link io.nanofaas.containerd.spi.ContainerdClient} implementation: owns the channel and the service facades. */
public final class DefaultContainerdClient implements ContainerdClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultContainerdClient.class);

    private final ManagedChannel channel;
    private final String namespace;
    private final String snapshotter;
    private final String runtimeName;
    private final Images images;
    // Concrete type (not the Containers SPI) so close() can shut down its IO virtual-thread pool.
    private final ContainersServiceImpl containers;
    private final Tasks tasks;
    // Concrete type (not the Events SPI) so close() can shut down its handler/reconnect executors.
    private final EventsServiceImpl events;

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName) {
        this(socketPath, namespace, snapshotter, runtimeName, runtimeBinaryName,
                ContainersServiceImpl.DEFAULT_STOP_TIMEOUT, null);
    }

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName,
                                   java.time.Duration stopTimeout) {
        this(socketPath, namespace, snapshotter, runtimeName, runtimeBinaryName, stopTimeout, null);
    }

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName,
                                   java.time.Duration stopTimeout,
                                   io.nanofaas.containerd.spi.ContainerNetwork network) {
        this(socketPath, namespace, snapshotter, runtimeName, runtimeBinaryName, stopTimeout,
                network, ContainersServiceImpl.DEFAULT_STATE_DIR);
    }

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName,
                                   java.time.Duration stopTimeout,
                                   io.nanofaas.containerd.spi.ContainerNetwork network,
                                   java.nio.file.Path stateDirectory) {
        this(socketPath, namespace, snapshotter, runtimeName, runtimeBinaryName, stopTimeout, network,
                stateDirectory, false);
    }

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName,
                                   java.time.Duration stopTimeout,
                                   io.nanofaas.containerd.spi.ContainerNetwork network,
                                   java.nio.file.Path stateDirectory, boolean systemdCgroup) {
        // Null from the builder means the caller expressed no preference.
        java.nio.file.Path state = stateDirectory == null
                ? ContainersServiceImpl.DEFAULT_STATE_DIR : stateDirectory;
        state = scopedStateDirectory(state, socketPath, namespace);
        this.namespace = namespace;
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
        // The NamespaceInterceptor is attached at the channel level, so EVERY call made through
        // this channel (version() and every facade built on it) carries the namespace header.
        this.channel = GrpcChannelFactory.createUnixDomainSocketChannel(socketPath,
                new NamespaceInterceptor(namespace));
        log.debug("containerd client created (namespace={}, snapshotter={}, runtime={}, binaryName={})",
                namespace, snapshotter, runtimeName, runtimeBinaryName);
        // One shared facade per client, cached in a final field (the plan says "lazily", but a
        // final field rules that out; building it here is free — it only constructs gRPC stubs).
        this.images = new ImagesServiceImpl(channel, snapshotter);
        this.containers = new ContainersServiceImpl(channel, snapshotter, runtimeName, runtimeBinaryName, stopTimeout,
                network, state, systemdCgroup);
        this.tasks = new TasksServiceImpl(channel, runtimeBinaryName, systemdCgroup);
        this.events = new EventsServiceImpl(channel, namespace);
    }

    private static java.nio.file.Path scopedStateDirectory(java.nio.file.Path root, String socket, String namespace) {
        String identity = java.nio.file.Path.of(socket).toAbsolutePath().normalize() + "\0" + namespace;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return root.resolve("scope-" + java.util.HexFormat.of().formatHex(digest));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    @Override
    public Images images() {
        return images;
    }

    @Override
    public Containers containers() {
        return containers;
    }

    @Override
    public Tasks tasks() {
        return tasks;
    }

    @Override
    public Events events() {
        return events;
    }

    @Override
    public Version version() {
        var response = containerd.services.version.v1.VersionGrpc.newBlockingStub(channel)
                .version(com.google.protobuf.Empty.getDefaultInstance());
        return new Version(response.getVersion(), response.getRevision());
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String snapshotter() {
        return snapshotter;
    }

    @Override
    public String runtimeName() {
        return runtimeName;
    }

    @Override
    public void close() {
        log.debug("closing containerd client");
        containers.close();
        events.close();
        channel.shutdownNow();
    }
}
