package io.nanofaas.containerd.internal;

import com.google.protobuf.Timestamp;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoMapperTest {

    @Test
    void mapsProtoContainer() {
        var proto = containerd.services.containers.v1.Container.newBuilder()
                .setId("abc-123")
                .setImage("docker.io/library/alpine:latest")
                .setSnapshotter("overlayfs")
                .setSnapshotKey("abc-123")
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1700000000).build())
                .putLabels("a", "b")
                .build();

        var container = ProtoMapper.map(proto);

        assertThat(container.id()).isEqualTo("abc-123");
        assertThat(container.image()).isEqualTo("docker.io/library/alpine:latest");
        assertThat(container.snapshotKey()).isEqualTo("abc-123");
        assertThat(container.createdAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
        assertThat(container.labels()).containsEntry("a", "b");
    }

    @Test
    void mapsProtoImage() {
        var proto = containerd.services.images.v1.Image.newBuilder()
                .setName("docker.io/library/alpine:latest")
                .setTarget(containerd.types.Descriptor.newBuilder().setDigest("sha256:abcdef").setSize(1234).build())
                .build();

        var image = ProtoMapper.map(proto);

        assertThat(image.name()).isEqualTo("docker.io/library/alpine:latest");
        assertThat(image.digest()).isEqualTo("sha256:abcdef");
        assertThat(image.size()).isEqualTo(1234L);
    }

    @Test
    void mapsEveryStatusTheVendoredEnumDefines() {
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.CREATED)).isEqualTo(ContainerState.CREATED);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.RUNNING)).isEqualTo(ContainerState.RUNNING);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.STOPPED)).isEqualTo(ContainerState.STOPPED);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.PAUSED)).isEqualTo(ContainerState.PAUSED);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.PAUSING)).isEqualTo(ContainerState.PAUSING);
    }

    @Test
    void mapsUnknownAndFutureStatusesToUnknown() {
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.UNKNOWN)).isEqualTo(ContainerState.UNKNOWN);
        // A status number a newer containerd introduces must not be guessed at.
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.UNRECOGNIZED)).isEqualTo(ContainerState.UNKNOWN);
        assertThat(containerd.v1.types.Status.forNumber(6))
                .as("v2.2.1 defines no status 6").isNull();
    }

    @Test
    void platformAndSignalDefaults() {
        assertThat(Platform.linuxAmd64().os()).isEqualTo("linux");
        assertThat(Platform.linuxAmd64().architecture()).isEqualTo("amd64");
        assertThat(Signal.TERM.number()).isEqualTo(15);
        assertThat(Signal.KILL.number()).isEqualTo(9);
    }
}
