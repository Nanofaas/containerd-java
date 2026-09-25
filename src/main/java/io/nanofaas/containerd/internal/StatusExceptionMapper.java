package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;

/** Maps gRPC status codes to library exceptions, preserving the original as cause. */
public final class StatusExceptionMapper {

    public enum ResourceKind { CONTAINER, IMAGE, TASK, SNAPSHOT, GENERAL }

    private StatusExceptionMapper() {
    }

    public static ContainerdException map(StatusRuntimeException e, ResourceKind kind) {
        String message = message(e, kind);
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
            return switch (kind) {
                case CONTAINER -> new ContainerNotFoundException(message, e);
                case IMAGE -> new ImageNotFoundException(message, e);
                case TASK -> new TaskNotFoundException(message, e);
                default -> new ContainerdException(message, e);
            };
        }
        if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS && kind == ResourceKind.CONTAINER) {
            return new ContainerAlreadyExistsException(message, e);
        }
        return new ContainerdException(message, e);
    }

    /**
     * Builds the exception message. containerd's own explanation travels in the status
     * description ("container does not exist", "snapshot does not exist: not found", ...);
     * without it the message is just a bare code, which says nothing the type does not already.
     */
    private static String message(StatusRuntimeException e, ResourceKind kind) {
        String message = "containerd " + kind.name().toLowerCase(java.util.Locale.ROOT) + " operation failed: "
                + e.getStatus().getCode();
        String description = e.getStatus().getDescription();
        return description == null || description.isBlank() ? message : message + ": " + description;
    }
}
