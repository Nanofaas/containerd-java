package io.nanofaas.containerd.cni;

import io.libcni.CNIConfig;
import io.libcni.ConfigLoader;
import io.libcni.NetworkConfigList;
import io.libcni.RuntimeConf;
import io.libcni.invoke.DefaultExec;
import io.libcni.types.Result;
import io.libcni.types.CurrentResult;
import io.nanofaas.containerd.NetworkAttachment;
import io.nanofaas.containerd.spi.ContainerNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Gives containers a network by running CNI plugins, the same way containerd's own consumers do.
 *
 * <p>Build one and hand it to the client:
 *
 * <pre>{@code
 * ContainerdClient client = ContainerdClient.builder()
 *         .network(CniContainerNetwork.builder().build())
 *         .build();
 *
 * client.containers().create(ContainerSpec.builder()
 *         .id("fn-1").image("...").network("mynet").build());
 * }</pre>
 *
 * <p>The client decides when to call this, which matters more than it looks: a container's network
 * namespace is its task's, so it exists only between the task starting and being torn down.
 */
public final class CniContainerNetwork implements ContainerNetwork {

    private static final Logger log = LoggerFactory.getLogger(CniContainerNetwork.class);

    /** Where CNI plugins live by convention, and where every runtime looks for them. */
    public static final Path DEFAULT_PLUGIN_DIR = Path.of("/opt/cni/bin");
    /** Where network configurations live by convention. */
    public static final Path DEFAULT_CONFIG_DIR = Path.of("/etc/cni/net.d");
    /** The interface name given to the container, matching what docker and CRI use. */
    public static final String DEFAULT_INTERFACE = "eth0";

    private final CNIConfig cni;
    private final Path configDir;
    private final String interfaceName;

    private CniContainerNetwork(Builder b) {
        this.configDir = b.configDir;
        this.interfaceName = b.interfaceName;
        this.cni = new CNIConfig(List.of(b.pluginDir.toString()), b.cacheDir.toString(),
                new DefaultExec(b.pluginTimeout.toMillis()));
    }

    /** {@return a builder using the default CNI paths and interface name} */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public NetworkAttachment attach(String containerId, String network, int pid) {
        NetworkConfigList list = configuration(network);
        Result result = cni.addNetworkList(list, runtimeConf(containerId, netnsOf(pid)));
        NetworkAttachment attachment = toAttachment(result);
        log.debug("attached {} to {}: {}", containerId, network, attachment);
        return attachment;
    }

    /**
     * Reads the addresses and DNS out of a CNI result.
     *
     * <p>Anything the result does not carry comes back empty rather than null: a network that
     * assigns no DNS is ordinary, and making every caller check for null would be the wrong
     * trade. A result shape this code does not recognise yields {@link NetworkAttachment#EMPTY} —
     * the container is attached either way, and the caller finding out nothing about it is better
     * than a failed start.
     */
    private static NetworkAttachment toAttachment(Result result) {
        if (!(result instanceof CurrentResult current)) {
            log.warn("CNI returned {}, which this code cannot read; the container is attached but"
                    + " nothing is known about its addressing", result == null ? "nothing"
                    : result.getClass().getName());
            return NetworkAttachment.EMPTY;
        }
        List<String> addresses = new ArrayList<>();
        List<String> gateways = new ArrayList<>();
        if (current.ips != null) {
            for (var ip : current.ips) {
                if (ip.address != null) {
                    addresses.add(ip.address);
                }
                if (ip.gateway != null) {
                    gateways.add(ip.gateway);
                }
            }
        }
        var dns = current.dns;
        return new NetworkAttachment(addresses, gateways,
                dns == null || dns.nameservers == null ? List.of() : dns.nameservers,
                dns == null || dns.search == null ? List.of() : dns.search,
                dns == null ? null : dns.domain);
    }

    @Override
    public void detach(String containerId, String network, int pid) {
        // An exited task has taken its namespace with it. The CNI spec allows DEL with no
        // namespace precisely for this, and IPAM releases the address by container id, so the
        // address comes back either way.
        String netns = pid > 0 && Files.exists(netnsPath(pid)) ? netnsOf(pid) : "";
        if (netns.isEmpty()) {
            log.debug("detaching {} from {} with no namespace: the task is already gone",
                    containerId, network);
        }
        cni.delNetworkList(configuration(network), runtimeConf(containerId, netns));
    }

    /**
     * Loads a network's configuration, failing with something a reader can act on. The usual cause
     * is a missing directory or a name that matches no file, and libcni's own message says neither.
     */
    private NetworkConfigList configuration(String network) {
        if (!Files.isDirectory(configDir)) {
            throw new IllegalStateException("no CNI configuration directory at " + configDir
                    + "; create it and put a .conflist for network \"" + network + "\" in it");
        }
        try {
            return ConfigLoader.loadNetworkConf(configDir.toString(), network);
        } catch (RuntimeException e) {
            throw new IllegalStateException("no CNI configuration named \"" + network + "\" in "
                    + configDir + " (the name is the \"name\" field inside the file, not its"
                    + " filename): " + e.getMessage(), e);
        }
    }

    private RuntimeConf runtimeConf(String containerId, String netns) {
        RuntimeConf rt = new RuntimeConf();
        rt.containerID = containerId;
        rt.netNS = netns;
        rt.ifName = interfaceName;
        return rt;
    }

    private static Path netnsPath(int pid) {
        return Path.of("/proc", String.valueOf(pid), "ns", "net");
    }

    private static String netnsOf(int pid) {
        return netnsPath(pid).toString();
    }

    /** Collects the directories and timeouts a {@link CniContainerNetwork} runs with. */
    public static final class Builder {

        private Path pluginDir = DEFAULT_PLUGIN_DIR;
        private Path configDir = DEFAULT_CONFIG_DIR;
        private Path cacheDir = Path.of("/var/lib/cni");
        private String interfaceName = DEFAULT_INTERFACE;
        private Duration pluginTimeout = Duration.ofSeconds(30);

        private Builder() {
        }

        /**
         * Where the CNI plugin binaries live.
         *
         * @param pluginDir plugin directory; defaults to {@link #DEFAULT_PLUGIN_DIR}
         * @return this builder
         */
        public Builder pluginDir(Path pluginDir) {
            this.pluginDir = Objects.requireNonNull(pluginDir, "pluginDir");
            return this;
        }

        /**
         * Where the network configurations live.
         *
         * @param configDir configuration directory; defaults to {@link #DEFAULT_CONFIG_DIR}
         * @return this builder
         */
        public Builder configDir(Path configDir) {
            this.configDir = Objects.requireNonNull(configDir, "configDir");
            return this;
        }

        /**
         * Where CNI caches attachment results, which DEL needs to undo what ADD did.
         *
         * @param cacheDir cache directory; defaults to {@code /var/lib/cni}
         * @return this builder
         */
        public Builder cacheDir(Path cacheDir) {
            this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
            return this;
        }

        /**
         * The interface name created inside the container.
         *
         * @param interfaceName interface name; defaults to {@value #DEFAULT_INTERFACE}
         * @return this builder
         */
        public Builder interfaceName(String interfaceName) {
            this.interfaceName = Objects.requireNonNull(interfaceName, "interfaceName");
            return this;
        }

        /**
         * How long a single plugin may run before it is killed.
         *
         * <p>There is a default because there should be: a plugin that hangs would otherwise hang
         * the container start that is waiting on it, with nothing to say why.
         *
         * @param pluginTimeout per-plugin timeout; defaults to 30 seconds
         * @return this builder
         */
        public Builder pluginTimeout(Duration pluginTimeout) {
            Objects.requireNonNull(pluginTimeout, "pluginTimeout");
            if (pluginTimeout.isNegative() || pluginTimeout.isZero()) {
                throw new IllegalArgumentException("pluginTimeout must be positive, got: " + pluginTimeout);
            }
            this.pluginTimeout = pluginTimeout;
            return this;
        }

        /**
         * Creates the networking implementation.
         *
         * @return a {@link CniContainerNetwork}
         * @throws IllegalStateException if the plugin directory does not exist, which is the
         *         failure worth catching early — without plugins nothing can be attached, and the
         *         error CNI gives at that point names neither the directory nor the reason
         */
        public CniContainerNetwork build() {
            if (!Files.isDirectory(pluginDir)) {
                throw new IllegalStateException("no CNI plugins at " + pluginDir
                        + ". Install them from https://github.com/containernetworking/plugins"
                        + " or point pluginDir(...) at where they are");
            }
            return new CniContainerNetwork(this);
        }
    }
}
