package io.nanofaas.containerd.internal;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Platform;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Choosing a manifest out of a multi-platform index. Picking the wrong one produces a container
 * whose binaries are all the wrong architecture, and the runtime's only complaint is
 * "exec format error" — which names nothing and points at nothing.
 */
class PlatformSelectionTest {

    private static String index(String... platforms) {
        StringBuilder entries = new StringBuilder();
        for (String platform : platforms) {
            String[] parts = platform.split("/");
            if (!entries.isEmpty()) {
                entries.append(',');
            }
            entries.append("""
                    {"mediaType":"application/vnd.oci.image.manifest.v1+json",
                     "digest":"sha256:manifest-%s-%s","size":10,
                     "platform":{"os":"%s","architecture":"%s"}}
                    """.formatted(parts[0], parts[1], parts[0], parts[1]));
        }
        return """
                {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[%s]}
                """.formatted(entries);
    }

    private static final String MANIFEST = """
            {"mediaType":"application/vnd.oci.image.manifest.v1+json",
             "config":{"digest":"sha256:config","size":10},"layers":[]}
            """;
    private static final String CONFIG = """
            {"rootfs":{"type":"layers","diff_ids":["sha256:only-layer"]}}
            """;

    /** Serves an image whose target is the given index, plus every manifest and config below it. */
    private static final class FakeRegistry implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;

        FakeRegistry(String indexJson) throws Exception {
            Map<String, String> blobs = new java.util.HashMap<>();
            blobs.put("sha256:index", indexJson);
            blobs.put("sha256:config", CONFIG);
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name).directExecutor()
                    .addService(containerd.services.images.v1.ImagesGrpc.bindService(
                            new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                                @Override
                                public void get(containerd.services.images.v1.GetImageRequest request,
                                                StreamObserver<containerd.services.images.v1.GetImageResponse> o) {
                                    o.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                            .setImage(containerd.services.images.v1.Image.newBuilder()
                                                    .setName(request.getName())
                                                    .setTarget(containerd.types.Descriptor.newBuilder()
                                                            .setDigest("sha256:index")))
                                            .build());
                                    o.onCompleted();
                                }
                            }))
                    .addService(containerd.services.content.v1.ContentGrpc.bindService(
                            new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                                private String blob(String digest) {
                                    return blobs.getOrDefault(digest, MANIFEST);
                                }

                                @Override
                                public void info(containerd.services.content.v1.InfoRequest request,
                                                 StreamObserver<containerd.services.content.v1.InfoResponse> o) {
                                    o.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                            .setInfo(containerd.services.content.v1.Info.newBuilder()
                                                    .setDigest(request.getDigest())
                                                    .setSize(blob(request.getDigest())
                                                            .getBytes(StandardCharsets.UTF_8).length))
                                            .build());
                                    o.onCompleted();
                                }

                                @Override
                                public void read(containerd.services.content.v1.ReadContentRequest request,
                                                 StreamObserver<containerd.services.content.v1.ReadContentResponse> o) {
                                    o.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                            .setData(ByteString.copyFromUtf8(blob(request.getDigest())))
                                            .build());
                                    o.onCompleted();
                                }
                            }))
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void resolvesAnIndexThatCarriesTheHostPlatform() throws Exception {
        Platform host = Platform.host();
        try (var fake = new FakeRegistry(index("linux/ppc64le", host.os() + "/" + host.architecture()))) {
            assertThat(new ImageRootfsResolver(fake.channel).resolve("multi:latest").chainId())
                    .isEqualTo("sha256:only-layer");
        }
    }

    @Test
    void refusesAnIndexWithoutTheHostPlatform() throws Exception {
        // What sonar-scanner-cli would do on arm64: an index offering only foreign architectures.
        // The old behaviour took the first entry and produced a container that could not exec.
        Platform host = Platform.host();
        String foreign = host.architecture().equals("arm64") ? "amd64" : "arm64";
        try (var fake = new FakeRegistry(index("linux/" + foreign, "linux/ppc64le"))) {
            var resolver = new ImageRootfsResolver(fake.channel);
            assertThatThrownBy(() -> resolver.resolve("foreign:latest"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(host.os() + "/" + host.architecture())
                    .hasMessageContaining(foreign);
        }
    }

    @Test
    void ignoresAttestationEntriesWhenExplainingWhatTheImageOffers() throws Exception {
        Platform host = Platform.host();
        String foreign = host.architecture().equals("arm64") ? "amd64" : "arm64";
        try (var fake = new FakeRegistry(index("linux/" + foreign, "unknown/unknown"))) {
            var resolver = new ImageRootfsResolver(fake.channel);
            assertThatThrownBy(() -> resolver.resolve("foreign:latest"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(foreign)
                    .as("attestation manifests are not platforms a caller could have chosen")
                    .hasMessageNotContaining("unknown");
        }
    }
}
