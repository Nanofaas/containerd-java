package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads what containerd needs to turn an image into a container: the ChainID of its top layer
 * (the snapshot parent key) and the configuration that shapes how it runs.
 */
public final class ImageRootfsResolver {

    private static final List<String> INDEX_MEDIA_TYPES = List.of(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json");
    private static final List<String> MANIFEST_MEDIA_TYPES = List.of(
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private static final String DIGEST = "digest";

    private final containerd.services.images.v1.ImagesGrpc.ImagesBlockingStub images;
    private final ContentStoreReader content;

    public ImageRootfsResolver(ManagedChannel channel) {
        this.images = containerd.services.images.v1.ImagesGrpc.newBlockingStub(channel);
        this.content = new ContentStoreReader(channel);
    }

    /**
     * An image's snapshot parent key and its run configuration.
     *
     * @param chainId ChainID of the top layer, empty for a scratch image
     * @param config the image's entrypoint, cmd, env, user and working directory
     */
    record ResolvedImage(String chainId, ImageConfig config) {
    }

    /**
     * Resolves an image to its snapshot parent key and run configuration, reading the manifest
     * and config blobs once for both.
     *
     * @param imageName image reference
     * @return the ChainID and the image configuration
     */
    ResolvedImage resolve(String imageName) {
        var image = images.get(containerd.services.images.v1.GetImageRequest.newBuilder()
                .setName(imageName).build()).getImage();

        var top = JsonSupport.parse(json(content.read(image.getTarget().getDigest())));
        String mediaType = field(top, "mediaType");
        if (INDEX_MEDIA_TYPES.contains(mediaType)) {
            // pick the first platform entry matching the host platform, else the first entry
            String manifestDigest = pickPlatformManifest(top);
            top = JsonSupport.parse(json(content.read(manifestDigest)));
            mediaType = field(top, "mediaType");
        }
        if (!MANIFEST_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalStateException("unsupported manifest media type: " + mediaType);
        }

        String configDigest = top.getFieldsOrThrow("config").getStructValue()
                .getFieldsOrThrow(DIGEST).getStringValue();
        var config = JsonSupport.parse(json(content.read(configDigest)));
        var diffIds = config.getFieldsOrThrow("rootfs").getStructValue()
                .getFieldsOrThrow("diff_ids").getListValue().getValuesList();

        List<String> ids = new ArrayList<>();
        for (var v : diffIds) {
            ids.add(v.getStringValue());
        }
        return new ResolvedImage(ChainIds.chainId(ids), imageConfig(config));
    }

    /**
     * Reads the {@code config} object of an image configuration. Every field is optional: an
     * image that declares none of them yields {@link ImageConfig#EMPTY}.
     */
    private static ImageConfig imageConfig(com.google.protobuf.Struct imageConfigJson) {
        var config = imageConfigJson
                .getFieldsOrDefault("config", com.google.protobuf.Value.getDefaultInstance())
                .getStructValue();
        if (config.getFieldsCount() == 0) {
            return ImageConfig.EMPTY;
        }
        String user = field(config, "User");
        String workingDir = field(config, "WorkingDir");
        return new ImageConfig(
                stringList(config, "Entrypoint"),
                stringList(config, "Cmd"),
                stringList(config, "Env"),
                user.isEmpty() ? null : user,
                workingDir.isEmpty() ? null : workingDir);
    }

    /** A JSON array of strings, empty when the field is absent or null (both occur in the wild). */
    private static List<String> stringList(com.google.protobuf.Struct struct, String name) {
        var value = struct.getFieldsOrDefault(name, com.google.protobuf.Value.getDefaultInstance());
        if (value.getKindCase() != com.google.protobuf.Value.KindCase.LIST_VALUE) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (var item : value.getListValue().getValuesList()) {
            items.add(item.getStringValue());
        }
        return items;
    }

    /**
     * Picks the manifest matching the host platform.
     *
     * <p>Fails when the index holds none. This used to fall back to the first entry, which is
     * usually the amd64 one: on an arm64 host that produced a container whose every binary was
     * the wrong architecture, and the only symptom was {@code exec format error} from the runtime,
     * naming nothing and pointing at nothing. Refusing here says which platform was wanted and
     * which the image actually offers.
     */
    private static String pickPlatformManifest(com.google.protobuf.Struct index) {
        var manifests = index.getFieldsOrThrow("manifests").getListValue().getValuesList();
        if (manifests.isEmpty()) {
            throw new IllegalStateException("image index has no manifests");
        }
        var host = io.nanofaas.containerd.Platform.host();
        List<String> offered = new ArrayList<>();
        for (var m : manifests) {
            var platform = m.getStructValue().getFieldsOrDefault("platform",
                    com.google.protobuf.Value.getDefaultInstance()).getStructValue();
            String os = field(platform, "os");
            String architecture = field(platform, "architecture");
            if (os.equals(host.os()) && architecture.equals(host.architecture())) {
                return m.getStructValue().getFieldsOrThrow(DIGEST).getStringValue();
            }
            // "unknown/unknown" entries are attestation manifests, not runnable images; leaving
            // them out of the message keeps it about what the caller could actually have asked for.
            if (!os.isEmpty() && !"unknown".equals(os)) {
                offered.add(os + "/" + architecture);
            }
        }
        throw new IllegalStateException("image has no manifest for " + host.os() + "/"
                + host.architecture() + "; it offers " + (offered.isEmpty() ? "no platform" : offered));
    }

    /** OCI manifests and configs are UTF-8 by specification, never the platform default. */
    private static String json(byte[] blob) {
        return new String(blob, StandardCharsets.UTF_8);
    }

    private static String field(com.google.protobuf.Struct struct, String name) {
        return struct.getFieldsOrDefault(name, com.google.protobuf.Value.getDefaultInstance()).getStringValue();
    }
}
