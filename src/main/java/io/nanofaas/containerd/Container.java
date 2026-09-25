package io.nanofaas.containerd;

import java.time.Instant;
import java.util.Map;

/**
 * A container as stored by containerd.
 *
 * @param id container identifier, unique within the namespace
 * @param image reference of the image the container was created from
 * @param snapshotter snapshotter holding the container's root filesystem
 * @param snapshotKey key of that snapshot
 * @param createdAt when containerd created the container, or {@link java.time.Instant#EPOCH} if it reported none
 * @param labels labels stored on the container, including containerd's own GC references
 */
public record Container(String id, String image, String snapshotter, String snapshotKey, Instant createdAt, Map<String, String> labels) {

    /** Defensively copies the labels, so the record cannot change under its holder. */
    public Container {
        labels = Map.copyOf(labels);
    }
}
