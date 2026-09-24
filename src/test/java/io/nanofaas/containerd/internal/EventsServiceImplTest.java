package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.EventFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventsServiceImplTest {

    /** An Events service that accepts a subscription and holds the stream open. */
    private static final class FakeEvents implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final CountDownLatch streamOpen = new CountDownLatch(1);
        final CountDownLatch streamCancelled = new CountDownLatch(1);
        final AtomicReference<StreamObserver<containerd.types.Envelope>> stream = new AtomicReference<>();
        final AtomicReference<containerd.services.events.v1.SubscribeRequest> request = new AtomicReference<>();
        /** Reaches zero on the second subscription, which only a reconnect makes. */
        final CountDownLatch resubscribed = new CountDownLatch(2);

        FakeEvents() throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name)
                    .addService(containerd.services.events.v1.EventsGrpc.bindService(
                            new containerd.services.events.v1.EventsGrpc.EventsImplBase() {
                                @Override
                                public void subscribe(containerd.services.events.v1.SubscribeRequest request,
                                                      StreamObserver<containerd.types.Envelope> o) {
                                    ((ServerCallStreamObserver<containerd.types.Envelope>) o)
                                            .setOnCancelHandler(streamCancelled::countDown);
                                    FakeEvents.this.request.set(request);
                                    resubscribed.countDown();
                                    stream.set(o);
                                    streamOpen.countDown();
                                }
                            }))
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).build();
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Collects everything logged through JUL, which is where gRPC reports callback failures. */
    private static final class JulCapture implements AutoCloseable {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger root = Logger.getLogger("");
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord entry) { records.add(entry); }

            @Override public void flush() {
                // nothing is buffered: publish() appends straight to the list
            }

            @Override public void close() {
                // the list outlives the handler; the enclosing close() detaches it
            }
        };

        JulCapture() {
            root.addHandler(handler);
        }

        List<Throwable> thrown() {
            return records.stream().map(LogRecord::getThrown).filter(t -> t != null).toList();
        }

        @Override
        public void close() {
            root.removeHandler(handler);
        }
    }

    @Test
    void subscribingScopesTheStreamToTheNamespaceAndNothingElse() throws Exception {
        // Topics are selected client-side: this containerd's fieldpath parser rejects combined
        // filters and silently falls back to an unfiltered stream.
        try (var fake = new FakeEvents()) {
            var events = new EventsServiceImpl(fake.channel, "nanofaas");
            events.subscribe(EventFilter.topics("/tasks/start", "/tasks/exit"), e -> { });
            assertThat(fake.streamOpen.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(fake.request.get().getFiltersList()).containsExactly("namespace==nanofaas");
            events.close();
        }
    }

    @Test
    void deliversDecodedEventsForTheSelectedTopicsOnly() throws Exception {
        try (var fake = new FakeEvents()) {
            var events = new EventsServiceImpl(fake.channel, "nanofaas");
            var received = new java.util.concurrent.LinkedBlockingQueue<io.nanofaas.containerd.Event>();
            events.subscribe(EventFilter.topics("/tasks/exit"), received::add);
            assertThat(fake.streamOpen.await(5, TimeUnit.SECONDS)).isTrue();

            fake.stream.get().onNext(containerd.types.Envelope.newBuilder().setTopic("/tasks/start").build());
            fake.stream.get().onNext(containerd.types.Envelope.newBuilder()
                    .setTopic("/tasks/exit").setNamespace("nanofaas")
                    .setEvent(com.google.protobuf.Any.pack(containerd.events.TaskExit.newBuilder()
                            .setContainerId("c1").setPid(42).setExitStatus(7).build()))
                    .build());

            var event = received.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.topic()).isEqualTo("/tasks/exit");
            assertThat(event.taskEvent()).isEqualTo(new io.nanofaas.containerd.TaskEvent("c1", 42, 7));
            assertThat(received.poll(200, TimeUnit.MILLISECONDS)).as("/tasks/start was not selected").isNull();
            events.close();
        }
    }

    @Test
    void reconnectsAfterTheStreamFails() throws Exception {
        try (var fake = new FakeEvents()) {
            var events = new EventsServiceImpl(fake.channel, "nanofaas");
            events.subscribe(EventFilter.all(), e -> { });
            assertThat(fake.streamOpen.await(5, TimeUnit.SECONDS)).isTrue();

            fake.stream.get().onError(io.grpc.Status.UNAVAILABLE.asRuntimeException());

            // The first retry comes after the initial one-second backoff.
            assertThat(fake.resubscribed.await(5, TimeUnit.SECONDS)).isTrue();
            events.close();
        }
    }

    @Test
    void closingASubscriptionCancelsTheUnderlyingStream() throws Exception {
        try (var fake = new FakeEvents()) {
            var events = new EventsServiceImpl(fake.channel, "nanofaas");
            var subscription = events.subscribe(EventFilter.topics("/tasks/exit"), e -> { });
            assertThat(fake.streamOpen.await(5, TimeUnit.SECONDS)).isTrue();

            subscription.close();

            assertThat(fake.streamCancelled.await(5, TimeUnit.SECONDS))
                    .as("server must observe the cancellation, not keep streaming into a dead subscription")
                    .isTrue();
            events.close();
        }
    }

    // Waits for something that must not happen: a reconnect that should not fire after close.
    // There is no condition to poll for an absence; only time can tell you it stayed away.
    @SuppressWarnings("java:S2925")
    @Test
    void closingTheServiceCancelsStreamsWithoutRejectingWorkOnCallbackThreads() throws Exception {
        try (var fake = new FakeEvents(); var jul = new JulCapture()) {
            var events = new EventsServiceImpl(fake.channel, "nanofaas");
            // The user never closes the Subscription; they just close the client.
            events.subscribe(EventFilter.all(), e -> { });
            assertThat(fake.streamOpen.await(5, TimeUnit.SECONDS)).isTrue();

            // Exactly what DefaultContainerdClient.close() does.
            events.close();
            fake.channel.shutdownNow();
            Thread.sleep(1000); // let any reconnect attempt fire

            assertThat(jul.thrown())
                    .as("shutting the client down must not throw on a gRPC callback thread")
                    .noneMatch(RejectedExecutionException.class::isInstance);
        }
    }

    @Test
    void closeIsIdempotentAndRejectsLateSubscriptions() throws Exception {
        try (var fake = new FakeEvents()) {
            var events = new EventsServiceImpl(fake.channel, "nanofaas");
            events.close();
            events.close(); // must not throw

            var everything = EventFilter.all();
            assertThatThrownBy(() -> events.subscribe(everything, e -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }
}
