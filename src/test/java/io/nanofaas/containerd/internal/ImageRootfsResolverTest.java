package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageRootfsResolverTest {

    private static final String MANIFEST_JSON = """
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "config": {
                "mediaType": "application/vnd.oci.image.config.v1+json",
                "digest": "sha256:config-digest",
                "size": 100
              },
              "layers": []
            }
            """;

    private static final String CONFIG_JSON = """
            {
              "rootfs": {
                "type": "layers",
                "diff_ids": ["sha256:d1", "sha256:d2"]
              }
            }
            """;

    @Test
    void resolvesChainIdFromManifestAndConfig() throws Exception {
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor();

        server.addService(containerd.services.images.v1.ImagesGrpc.bindService(
                new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                    @Override
                    public void get(containerd.services.images.v1.GetImageRequest request,
                                    StreamObserver<containerd.services.images.v1.GetImageResponse> responseObserver) {
                        responseObserver.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                .setImage(containerd.services.images.v1.Image.newBuilder()
                                        .setName(request.getName())
                                        .setTarget(containerd.types.Descriptor.newBuilder()
                                                .setDigest("sha256:manifest-digest")
                                                .setSize(MANIFEST_JSON.length()))
                                        .build())
                                .build());
                        responseObserver.onCompleted();
                    }
                }));

        server.addService(containerd.services.content.v1.ContentGrpc.bindService(
                new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                    @Override
                    public void info(containerd.services.content.v1.InfoRequest request,
                                     StreamObserver<containerd.services.content.v1.InfoResponse> responseObserver) {
                        byte[] data = request.getDigest().equals("sha256:config-digest")
                                ? CONFIG_JSON.getBytes() : MANIFEST_JSON.getBytes();
                        responseObserver.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                .setInfo(containerd.services.content.v1.Info.newBuilder()
                                        .setDigest(request.getDigest())
                                        .setSize(data.length))
                                .build());
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void read(containerd.services.content.v1.ReadContentRequest request,
                                     StreamObserver<containerd.services.content.v1.ReadContentResponse> responseObserver) {
                        byte[] data = request.getDigest().equals("sha256:config-digest")
                                ? CONFIG_JSON.getBytes() : MANIFEST_JSON.getBytes();
                        responseObserver.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                .setOffset(0)
                                .setData(com.google.protobuf.ByteString.copyFrom(data))
                                .build());
                        responseObserver.onCompleted();
                    }
                }));

        server.build().start();
        try {
            ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                String chainId = new ImageRootfsResolver(channel).resolve("alpine:latest").chainId();
                assertThat(chainId).isEqualTo(ChainIds.chainId(List.of("sha256:d1", "sha256:d2")));
            } finally {
                channel.shutdownNow();
            }
        } finally {
            server.build().shutdownNow();
        }
    }
}
