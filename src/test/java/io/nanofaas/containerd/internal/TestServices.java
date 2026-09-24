package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.nanofaas.containerd.spi.ContainerNetwork;

import java.nio.file.Path;
import java.time.Duration;

/** The services with the client's defaults, so a test names only the settings it is about. */
final class TestServices {

    private TestServices() {
    }

    static ContainersServiceImpl containers(ManagedChannel channel) {
        return containers(channel, Duration.ofSeconds(10), null, ContainersServiceImpl.DEFAULT_STATE_DIR);
    }

    static ContainersServiceImpl containers(ManagedChannel channel, Duration stopTimeout,
                                            ContainerNetwork network, Path stateDirectory) {
        return new ContainersServiceImpl(channel, "overlayfs", "io.containerd.runc.v2", null,
                stopTimeout, network, stateDirectory, false);
    }

    static TasksServiceImpl tasks(ManagedChannel channel) {
        return new TasksServiceImpl(channel, null, false);
    }
}
