package io.nanofaas.containerd;

import com.sun.net.httpserver.HttpServer;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryHostsIT extends ContainerdConnectionIT {
    @TempDir
    Path hostsDirectory;

    @Test
    @Timeout(60)
    void pullsThroughAnHttpMirrorConfiguredOnlyOnTheClient() throws Exception {
        byte[] tar = new byte[1024]; // Two end-of-archive blocks form an empty, valid tar layer.
        byte[] layer;
        try (var buffer = new ByteArrayOutputStream()) {
            try (var gzip = new GZIPOutputStream(buffer)) {
                gzip.write(tar);
            }
            layer = buffer.toByteArray();
        }
        byte[] config = utf8("""
                {"architecture":"%s","os":"linux","config":{},
                 "rootfs":{"type":"layers","diff_ids":["%s"]}}
                """.formatted(Platform.host().architecture(), digest(tar)));
        byte[] manifest = utf8("""
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
                 "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"%s","size":%d},
                 "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"%s","size":%d}]}
                """.formatted(digest(config), config.length, digest(layer), layer.length));
        Map<String, byte[]> responses = Map.of(
                "/v2/", utf8("{}"),
                "/v2/greet/manifests/latest", manifest,
                "/v2/greet/manifests/" + digest(manifest), manifest,
                "/v2/greet/blobs/" + digest(config), config,
                "/v2/greet/blobs/" + digest(layer), layer);

        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = responses.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                boolean isManifest = exchange.getRequestURI().getPath().contains("/manifests/");
                exchange.getResponseHeaders().set("Content-Type", isManifest
                        ? "application/vnd.oci.image.manifest.v1+json" : "application/octet-stream");
                exchange.getResponseHeaders().set("Docker-Content-Digest", digest(body));
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
        try {
            // The synthetic registry cannot resolve through DNS: only hosts.toml can route this pull.
            String registry = "registry-" + UUID.randomUUID() + ".invalid";
            String image = registry + "/greet:latest";
            String host = System.getProperty("io.nanofaas.containerd.registry-fixture-host", "127.0.0.1");
            String endpoint = "http://" + host + ":" + server.getAddress().getPort();
            Files.createDirectory(hostsDirectory.resolve(registry));
            Files.writeString(hostsDirectory.resolve(registry).resolve("hosts.toml"), """
                    server = "%s"
                    [host."%s"]
                      capabilities = ["pull", "resolve"]
                    """.formatted(endpoint, endpoint));
            try (var configured = ContainerdClient.builder().socketPath(SOCKET)
                    .namespace("registry-it-" + UUID.randomUUID()).snapshotter("native")
                    .registryHostsDirectory(hostsDirectory).build()) {
                try {
                    configured.images().pull(image);
                    assertThat(configured.images().get(image).digest()).isEqualTo(digest(manifest));
                } finally {
                    configured.images().remove(image);
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the JDK", e);
        }
    }
}
