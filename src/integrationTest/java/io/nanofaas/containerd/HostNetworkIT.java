package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Host networking, proved against a listener on the host rather than against the internet:
 * the test opens a socket in this JVM and has the container connect back to it on loopback.
 * The negative case is the point — without the flag the same container must fail — otherwise
 * the test would pass whatever the flag did.
 */
@Tag("integration")
class HostNetworkIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";
    private static ExecutorService responders;

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
        responders = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void stopResponders() {
        responders.shutdownNow();
    }

    /** Answers one connection with PONG, then closes. Returns the port it listens on. */
    private static ServerSocket listenOnce() throws Exception {
        ServerSocket server = new ServerSocket(0);
        responders.execute(() -> {
            try (Socket socket = server.accept(); OutputStream out = socket.getOutputStream()) {
                out.write("PONG\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
                // the test asserts on the container's side
            }
        });
        return server;
    }

    private static String reachHostFrom(String id, int port) {
        ExecResult result = client.containers().exec(id, List.of("/bin/sh", "-c",
                "nc -w 3 127.0.0.1 " + port + " 2>&1 || echo UNREACHABLE"));
        return (result.stdout() + result.stderr()).trim();
    }

    @Test
    @Timeout(180)
    void withHostNetworkTheContainerReachesAListenerOnTheHost() throws Exception {
        String id = "it-hostnet-" + UUID.randomUUID();
        try (ServerSocket server = listenOnce()) {
            client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                    .hostNetwork(true)
                    .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
            try {
                client.containers().start(id);

                assertThat(reachHostFrom(id, server.getLocalPort()))
                        .as("the container shares the host's loopback, so it reaches the listener")
                        .contains("PONG");
            } finally {
                client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
            }
        }
    }

    @Test
    @Timeout(180)
    void withoutHostNetworkTheContainerIsIsolated() throws Exception {
        String id = "it-privnet-" + UUID.randomUUID();
        try (ServerSocket server = listenOnce()) {
            client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                    .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
            try {
                client.containers().start(id);

                assertThat(reachHostFrom(id, server.getLocalPort()))
                        .as("the default network namespace is empty: the host's loopback is not the container's")
                        .doesNotContain("PONG");
            } finally {
                client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
            }
        }
    }

    @Test
    @Timeout(180)
    void hostNetworkExposesTheHostInterfacesNotJustLoopback() {
        String id = "it-hostnet-if-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .hostNetwork(true)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);

            String interfaces = client.containers()
                    .exec(id, List.of("/bin/sh", "-c", "ip -o link show | wc -l")).stdout().trim();

            assertThat(Integer.parseInt(interfaces))
                    .as("a private namespace has only lo; the host has more")
                    .isGreaterThan(1);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
