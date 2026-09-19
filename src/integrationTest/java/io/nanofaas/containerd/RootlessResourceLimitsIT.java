package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Run explicitly inside the rootless daemon's namespaces; missing delegation is a failure. */
class RootlessResourceLimitsIT {
    @Test
    @Timeout(180)
    void crunEnforcesQuotaWeightMemoryReservationAndCpuset() throws Exception {
        String prefix = "io.nanofaas.containerd.";
        String socket = System.getProperty(prefix + "socket");
        assertThat(socket).as("set -Dio.nanofaas.containerd.socket to a rootless containerd socket").isNotBlank();
        String id = "rootless-limits-" + UUID.randomUUID();
        String image = System.getProperty(prefix + "image", "docker.io/library/alpine:3.21");
        String cpus = System.getProperty(prefix + "cpus", "0");
        String slice = System.getProperty(prefix + "cgroupSlice", "user.slice");
        Path state = Path.of(System.getProperty(prefix + "stateDirectory", "build/rootless-state")).toAbsolutePath();
        try (var client = ContainerdClient.builder().socketPath(socket).namespace("nanofaas-rootless-it")
                .snapshotter(System.getProperty(prefix + "snapshotter", "native"))
                .runtimeBinaryName(System.getProperty(prefix + "runtimeBinaryName", "crun"))
                .systemdCgroup(true).stateDirectory(state).build()) {
            client.images().pull(image);
            client.containers().create(ContainerSpec.builder().id(id).image(image)
                    .command(List.of("sleep", "300")).cpuShares(512)
                    .cpuQuotaMicros(50000).cpuPeriodMicros(100000).cpuSetCpus(cpus)
                    .memoryLimitBytes(134217728).memoryReservationBytes(67108864)
                    .cgroupsPath(slice + ":nanofaas:" + id).build());
            try {
                int pid = client.containers().start(id);
                String membership = Files.readAllLines(Path.of("/proc", Integer.toString(pid), "cgroup")).stream()
                        .filter(line -> line.startsWith("0::")).findFirst().orElseThrow();
                Path cgroup = Path.of("/sys/fs/cgroup").resolve(membership.substring(3).replaceFirst("^/", ""));
                assertThat(Files.readString(cgroup.resolve("cpu.max")).trim()).isEqualTo("50000 100000");
                // OCI shares 512 -> cgroup v2 weight 1 + ((512 - 2) * 9999) / 262142 = 20.
                assertThat(Files.readString(cgroup.resolve("cpu.weight")).trim()).isEqualTo("20");
                assertThat(Files.readString(cgroup.resolve("memory.max")).trim()).isEqualTo("134217728");
                assertThat(Files.readString(cgroup.resolve("memory.low")).trim()).isEqualTo("67108864");
                assertThat(Files.readString(cgroup.resolve("cpuset.cpus")).trim()).isEqualTo(cpus);
            } finally {
                client.containers().remove(id, RemoveOptions.builder().force(true).removeSnapshot(true).build());
            }
            assertThat(client.containers().pendingRemovals()).isEmpty();
        }
    }
}
