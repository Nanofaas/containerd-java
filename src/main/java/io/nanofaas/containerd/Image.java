package io.nanofaas.containerd;

import java.time.Instant;
import java.util.Map;

/**
 * An image as stored in containerd's image store.
 *
 * @param name image reference, for example {@code docker.io/library/alpine:latest}
 * @param digest digest of the image's target descriptor
 * @param size size in bytes of that target descriptor
 * @param createdAt when the image entered the store, or {@link java.time.Instant#EPOCH} if unreported
 * @param labels labels stored on the image
 */
public record Image(String name, String digest, long size, Instant createdAt, Map<String, String> labels) {

    /** Defensively copies the labels, so the record cannot change under its holder. */
    public Image {
        labels = Map.copyOf(labels);
    }
}
