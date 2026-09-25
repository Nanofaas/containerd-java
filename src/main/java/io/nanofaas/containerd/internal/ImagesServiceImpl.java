package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.Image;
import io.nanofaas.containerd.ImagePullException;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.spi.Images;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Image operations. Pulls go through containerd's Transfer service (the mechanism ctr uses):
 * registry source + image-store destination with unpacking into the snapshotter.
 */
public final class ImagesServiceImpl implements Images {

    private static final Logger log = LoggerFactory.getLogger(ImagesServiceImpl.class);

    private final containerd.services.images.v1.ImagesGrpc.ImagesBlockingStub stub;
    private final containerd.services.transfer.v1.TransferGrpc.TransferBlockingStub transfer;
    private final String snapshotter;

    public ImagesServiceImpl(ManagedChannel channel, String snapshotter) {
        this.stub = containerd.services.images.v1.ImagesGrpc.newBlockingStub(channel);
        this.transfer = containerd.services.transfer.v1.TransferGrpc.newBlockingStub(channel);
        this.snapshotter = snapshotter;
    }

    @Override
    public void pull(String reference) {
        pull(reference, Platform.host());
    }

    @Override
    public void pull(String reference, Platform platform) {
        log.debug("pull start: reference={} platform={}/{} snapshotter={}",
                reference, platform.os(), platform.architecture(), snapshotter);
        var protoPlatform = ProtoMapper.toProto(platform);
        var source = containerd.types.transfer.OCIRegistry.newBuilder()
                .setReference(reference)
                .build();
        var destination = containerd.types.transfer.ImageStore.newBuilder()
                .setName(reference)
                .addPlatforms(protoPlatform)
                .setAllMetadata(true)
                .addUnpacks(containerd.types.transfer.UnpackConfiguration.newBuilder()
                        .setPlatform(protoPlatform)
                        .setSnapshotter(snapshotter))
                .build();
        try {
            // The Transfer RPC blocks until the transfer completes.
            transfer.transfer(containerd.services.transfer.v1.TransferRequest.newBuilder()
                    .setSource(TypeUrls.pack(source))
                    .setDestination(TypeUrls.pack(destination))
                    .build());
        } catch (StatusRuntimeException e) {
            throw new ImagePullException("failed to pull image " + reference + ": " + e.getStatus(), e);
        }
        log.debug("pull complete: reference={}", reference);
    }

    @Override
    public Image get(String name) {
        try {
            var response = stub.get(containerd.services.images.v1.GetImageRequest.newBuilder()
                    .setName(name).build());
            return ProtoMapper.map(response.getImage());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }

    @Override
    public List<Image> list() {
        try {
            return stub.list(containerd.services.images.v1.ListImagesRequest.getDefaultInstance())
                    .getImagesList().stream()
                    .map(ProtoMapper::map)
                    .toList();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }

    @Override
    public void remove(String name) {
        log.debug("image remove: name={}", name);
        try {
            stub.delete(containerd.services.images.v1.DeleteImageRequest.newBuilder()
                    .setName(name)
                    .setSync(true)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.debug("image {} already gone (idempotent remove)", name);
                return;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }
}
