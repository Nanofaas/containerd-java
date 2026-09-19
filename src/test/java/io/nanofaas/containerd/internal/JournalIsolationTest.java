package io.nanofaas.containerd.internal;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Container;
import io.nanofaas.containerd.ContainerdException;
import io.nanofaas.containerd.RemoveOptions;
import io.nanofaas.containerd.spi.ContainerdClient;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalIsolationTest {
    @TempDir Path state;
    private static final Context.Key<String> NAMESPACE = Context.key("namespace");

    @Test
    void pendingCleanupIsIsolatedByDaemonAndNamespaceAcrossClientRestart() throws Exception {
        Path socket = state.resolve("daemon.sock");
        var group = new EpollEventLoopGroup(1);
        var server = NettyServerBuilder.forAddress(new DomainSocketAddress(socket.toString()))
                .channelType(EpollServerDomainSocketChannel.class).bossEventLoopGroup(group).workerEventLoopGroup(group)
                .intercept(new ServerInterceptor() {
                    @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call,
                            Metadata headers, ServerCallHandler<Q, R> next) {
                        return Contexts.interceptCall(Context.current().withValue(NAMESPACE,
                                headers.get(Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER))),
                                call, headers, next);
                    }
                })
                .addService(new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                    @Override public void get(containerd.services.containers.v1.GetContainerRequest request,
                            StreamObserver<containerd.services.containers.v1.GetContainerResponse> o) {
                        o.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                .setContainer(containerd.services.containers.v1.Container.newBuilder()
                                        .setId(request.getId()).setSnapshotter("native").setSnapshotKey(request.getId())
                                        .putLabels("owner", NAMESPACE.get())).build());
                        o.onCompleted();
                    }
                    @Override public void delete(containerd.services.containers.v1.DeleteContainerRequest request,
                            StreamObserver<com.google.protobuf.Empty> o) {
                        o.onNext(com.google.protobuf.Empty.getDefaultInstance()); o.onCompleted();
                    }
                })
                .addService(new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                    @Override public void get(containerd.services.tasks.v1.GetRequest request,
                            StreamObserver<containerd.services.tasks.v1.GetResponse> o) {
                        o.onError(Status.NOT_FOUND.asRuntimeException());
                    }
                })
                .addService(new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                    @Override public void remove(containerd.services.snapshots.v1.RemoveSnapshotRequest request,
                            StreamObserver<com.google.protobuf.Empty> o) {
                        o.onError(Status.INTERNAL.withDescription("snapshot busy").asRuntimeException());
                    }
                }).build().start();
        try {
            try (var one = client(socket, "one"); var two = client(socket, "two")) {
                assertThatThrownBy(() -> one.containers().remove("same-id", RemoveOptions.builder().removeSnapshot(true).build()))
                        .isInstanceOf(ContainerdException.class);
                assertThat(two.containers().pendingRemovals()).isEmpty();
                assertThatThrownBy(() -> two.containers().remove("same-id", RemoveOptions.builder().removeSnapshot(true).build()))
                        .isInstanceOf(ContainerdException.class);
            }
            try (var one = client(socket, "one"); var two = client(socket, "two");
                 var otherDaemon = client(state.resolve("other.sock"), "one")) {
                assertThat(one.containers().pendingRemovals()).extracting(Container::labels)
                        .allSatisfy(labels -> assertThat(labels).containsEntry("owner", "one"));
                assertThat(two.containers().pendingRemovals()).extracting(Container::labels)
                        .allSatisfy(labels -> assertThat(labels).containsEntry("owner", "two"));
                assertThat(one.containers().pendingRemovals()).hasSize(1);
                assertThat(two.containers().pendingRemovals()).hasSize(1);
                assertThat(otherDaemon.containers().pendingRemovals()).isEmpty();
            }
        } finally {
            server.shutdownNow().awaitTermination();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private ContainerdClient client(Path socket, String namespace) {
        return ContainerdClient.builder().socketPath(socket.toString()).namespace(namespace).stateDirectory(state).build();
    }
}
