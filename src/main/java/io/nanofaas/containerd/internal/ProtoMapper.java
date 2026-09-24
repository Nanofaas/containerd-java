package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.*;

import java.time.Instant;
import java.util.Map;

/** Conversions between containerd protobuf messages and the public model. */
public final class ProtoMapper {

    private ProtoMapper() {
    }

    public static Container map(containerd.services.containers.v1.Container c) {
        return new Container(
                c.getId(),
                c.getImage(),
                c.getSnapshotter(),
                c.getSnapshotKey(),
                c.hasCreatedAt() ? Instant.ofEpochSecond(c.getCreatedAt().getSeconds(), c.getCreatedAt().getNanos()) : Instant.EPOCH,
                Map.copyOf(c.getLabelsMap()));
    }

    public static Image map(containerd.services.images.v1.Image i) {
        return new Image(
                i.getName(),
                i.getTarget().getDigest(),
                i.getTarget().getSize(),
                i.hasCreatedAt() ? Instant.ofEpochSecond(i.getCreatedAt().getSeconds(), i.getCreatedAt().getNanos()) : Instant.EPOCH,
                Map.copyOf(i.getLabelsMap()));
    }

    /**
     * Maps containerd's task status. Anything the vendored v2.2.1 enum does not define — a value
     * from a newer containerd, or UNRECOGNIZED — becomes {@link ContainerState#UNKNOWN} rather
     * than being guessed at.
     */
    public static ContainerState mapStatus(containerd.v1.types.Status status) {
        return switch (status) {
            case CREATED -> ContainerState.CREATED;
            case RUNNING -> ContainerState.RUNNING;
            case STOPPED -> ContainerState.STOPPED;
            case PAUSED -> ContainerState.PAUSED;
            case PAUSING -> ContainerState.PAUSING;
            case UNKNOWN, UNRECOGNIZED -> ContainerState.UNKNOWN;
        };
    }

    public static containerd.types.Platform toProto(Platform platform) {
        return containerd.types.Platform.newBuilder()
                .setOs(platform.os())
                .setArchitecture(platform.architecture())
                .build();
    }
}
