package io.nanofaas.containerd;

import java.util.Arrays;
import java.util.List;

/**
 * Selects which containerd events to receive: only events in the client's namespace whose topic
 * is in {@link #topics()} (an empty topic list means every topic in the namespace).
 */
public final class EventFilter {

    private final List<String> topics;

    private EventFilter(List<String> topics) {
        this.topics = List.copyOf(topics);
    }

    /**
     * Selects the given topics. Note that {@code EventFilter.topics()} with no argument does not
     * compile — it resolves against the instance getter {@link #topics()} — so the "every topic"
     * filter has its own factory: {@link #all()}.
     *
     * @param topics topics to receive, for example {@code /tasks/exit}
     * @return a filter selecting those topics
     */
    public static EventFilter topics(String... topics) {
        return new EventFilter(Arrays.asList(topics));
    }

    /** {@return a filter matching every topic in the client's namespace} */
    public static EventFilter all() {
        return new EventFilter(List.of());
    }

    /** {@return the selected topics; empty means every topic in the namespace} */
    public List<String> topics() {
        return topics;
    }
}
