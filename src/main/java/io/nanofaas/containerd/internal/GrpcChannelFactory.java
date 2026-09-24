package io.nanofaas.containerd.internal;

import io.grpc.ClientInterceptor;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;

/** Creates gRPC channels over Unix Domain Sockets using the Netty epoll transport. */
public final class GrpcChannelFactory {

    private GrpcChannelFactory() {
    }

    /**
     * Creates a channel with the given {@link ClientInterceptor}s attached at the channel level
     * (via {@link NettyChannelBuilder#intercept}), so every call made through the returned channel
     * passes through them.
     *
     * <p>grpc-netty does not auto-select a domain-socket channel/group for a
     * {@link DomainSocketAddress}: its defaults are TCP-only, it requires channel type and event
     * loop group to be provided together, and it never shuts down a caller-supplied group. This
     * factory therefore owns an epoll group per channel and shuts it down once the channel is
     * shut down, so no non-daemon threads outlive {@code close()}.
     */
    public static ManagedChannel createUnixDomainSocketChannel(String socketPath,
                                                               ClientInterceptor... interceptors) {
        if (!Epoll.isAvailable()) {
            throw new IllegalStateException(
                    "Netty epoll native transport is not available; add netty-transport-native-epoll with the linux-x86_64 or linux-aarch_64 classifier");
        }
        EpollEventLoopGroup eventLoopGroup = new EpollEventLoopGroup();
        ManagedChannel channel = NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath))
                .channelType(EpollDomainSocketChannel.class)
                .eventLoopGroup(eventLoopGroup)
                .usePlaintext()
                .intercept(interceptors)
                .build();
        releaseEventLoopGroupOnTermination(channel, eventLoopGroup);
        return channel;
    }

    /**
     * Watches the channel's connectivity state and shuts the event loop group down once the
     * channel has been shut down. {@link ConnectivityState#SHUTDOWN} is the last public state a
     * channel reaches (there is no public TERMINAL state), and the netty channel close triggered
     * by grpc's shutdown is in flight by then; {@code shutdownGracefully()}'s quiet period lets
     * that close (and anything else already queued on the event loops) finish before the group's
     * threads terminate. The watcher re-arms itself on every transition; grpc invokes the
     * callback immediately when the state has already moved past the observed source, so no
     * transition can be missed.
     */
    // shutdownGracefully() is not awaited: the quiet period runs on the group's own threads, off
    // the caller's path.
    @SuppressWarnings("FutureReturnValueIgnored")
    private static void releaseEventLoopGroupOnTermination(ManagedChannel channel,
                                                           EpollEventLoopGroup eventLoopGroup) {
        ConnectivityState state = channel.getState(false);
        if (state == ConnectivityState.SHUTDOWN) {
            eventLoopGroup.shutdownGracefully();
            return;
        }
        channel.notifyWhenStateChanged(state,
                () -> releaseEventLoopGroupOnTermination(channel, eventLoopGroup));
    }
}
