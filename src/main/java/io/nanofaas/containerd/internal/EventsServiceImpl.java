package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import io.nanofaas.containerd.Subscription;
import io.nanofaas.containerd.spi.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * containerd event stream. Subscriptions run the user handler on a virtual thread (a slow
 * handler never blocks the gRPC callback thread) and reconnect with exponential backoff
 * (1s, doubling, capped at 30s) after a stream error until the subscription is closed.
 */
public final class EventsServiceImpl implements Events {

    private static final Logger log = LoggerFactory.getLogger(EventsServiceImpl.class);
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final containerd.services.events.v1.EventsGrpc.EventsStub stub;
    /**
     * The server-side fieldpath filter: scope the stream to the client's namespace. This is the
     * filter the Events service documents and the only one this containerd reliably applies on its
     * own. Topic selection is done client-side, because this containerd's fieldpath parser rejects
     * multi-filter combinations (and unquoted {@code /} values) and silently falls back to an
     * unfiltered stream.
     */
    private final String namespaceFilter;
    /** Set once {@link #close()} runs: stops every subscription and every executor submission. */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Live subscriptions, so {@link #close()} can cancel their in-flight gRPC calls. */
    private final Set<StreamSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService handlerExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "containerd-events-reconnect");
                t.setDaemon(true);
                return t;
            });

    public EventsServiceImpl(ManagedChannel channel, String namespace) {
        this.stub = containerd.services.events.v1.EventsGrpc.newStub(channel);
        this.namespaceFilter = "namespace==" + namespace;
    }

    @Override
    public Subscription subscribe(EventFilter filter, Consumer<Event> handler) {
        log.debug("events subscribe: filter={} topics={}", namespaceFilter, filter.topics());
        var subscription = new StreamSubscription(filter, handler);
        subscriptions.add(subscription);
        if (closed.get()) { // the client was closed concurrently with this call
            subscriptions.remove(subscription);
            throw new IllegalStateException("containerd client is closed");
        }
        subscription.connect();
        return subscription;
    }

    /** Client-side topic selection: an empty topic list matches every topic. */
    private static boolean matches(EventFilter filter, String topic) {
        List<String> topics = filter.topics();
        return topics.isEmpty() || topics.contains(topic);
    }

    /**
     * Shuts the service down: every subscription is cancelled (so no gRPC callback can schedule
     * further work) before the executors that would run that work are stopped. Doing it in the
     * other order let a stream failing during shutdown reach a terminated scheduler and throw
     * {@link RejectedExecutionException} on a gRPC callback thread.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (StreamSubscription subscription : Set.copyOf(subscriptions)) {
            subscription.close();
        }
        handlerExecutor.shutdown();
        reconnectScheduler.shutdown();
    }

    /** One subscription: its filter, its handler, and the gRPC call currently carrying it. */
    private final class StreamSubscription implements Subscription {

        private final EventFilter filter;
        private final Consumer<Event> handler;
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean(false);
        private final AtomicLong backoff = new AtomicLong(INITIAL_BACKOFF_MS);
        private final AtomicReference<ClientCallStreamObserver<?>> call = new AtomicReference<>();

        StreamSubscription(EventFilter filter, Consumer<Event> handler) {
            this.filter = filter;
            this.handler = handler;
        }

        /** Whether this subscription should stop: it was closed, or the whole service was. */
        private boolean stopped() {
            return subscriptionClosed.get() || closed.get();
        }

        void connect() {
            if (stopped()) {
                return;
            }
            var request = containerd.services.events.v1.SubscribeRequest.newBuilder()
                    .addFilters(namespaceFilter)
                    .build();
            stub.subscribe(request, new ClientResponseObserver<
                    containerd.services.events.v1.SubscribeRequest, containerd.types.Envelope>() {

                @Override
                public void beforeStart(ClientCallStreamObserver<
                        containerd.services.events.v1.SubscribeRequest> requestStream) {
                    // Hold the call so close() can cancel it; without this the server keeps
                    // streaming into a subscription nobody reads for the life of the channel.
                    call.set(requestStream);
                    if (stopped()) {
                        requestStream.cancel("subscription closed", null);
                    }
                }

                @Override
                public void onNext(containerd.types.Envelope envelope) {
                    if (stopped()) {
                        return;
                    }
                    backoff.set(INITIAL_BACKOFF_MS); // the stream is healthy: reset the backoff
                    // The stream is scoped to the namespace server-side; select topics client-side,
                    // because this containerd's fieldpath parser rejects multi-filter combinations.
                    if (!matches(filter, envelope.getTopic())) {
                        return;
                    }
                    Event event = EventMapper.map(envelope);
                    try {
                        handlerExecutor.submit(() -> {
                            try {
                                handler.accept(event);
                            } catch (Exception e) {
                                log.warn("event handler threw for topic {}", event.topic(), e);
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        // the client was closed between the stopped() check and the submit
                        log.debug("dropping event {} : client is closing", event.topic());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    handleStreamEnd(t);
                }

                @Override
                public void onCompleted() {
                    handleStreamEnd(null);
                }
            });
        }

        private void handleStreamEnd(Throwable error) {
            if (stopped()) {
                return;
            }
            if (error instanceof StatusRuntimeException sre
                    && sre.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
                log.error("events not supported by this containerd; giving up");
                return;
            }
            long delay = backoff.getAndUpdate(current -> Math.min(current * 2, MAX_BACKOFF_MS));
            if (error != null) {
                log.warn("events stream error (reconnecting in {}ms): {}", delay, error.getMessage());
            } else {
                log.warn("events stream ended (reconnecting in {}ms)", delay);
            }
            try {
                reconnectScheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // the client was closed between the stopped() check and the schedule
                log.debug("not reconnecting events stream: client is closing");
            }
        }

        @Override
        public void close() {
            if (!subscriptionClosed.compareAndSet(false, true)) {
                return;
            }
            subscriptions.remove(this);
            ClientCallStreamObserver<?> current = call.getAndSet(null);
            if (current != null) {
                current.cancel("subscription closed", null);
            }
            log.debug("events subscription closed");
        }
    }
}
