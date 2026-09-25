package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.nanofaas.containerd.Event;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventMapperTest {

    @Test
    void decodesTaskStartPayload() {
        var payload = containerd.events.TaskStart.newBuilder().setContainerId("abc").setPid(77).build();
        var envelope = containerd.types.Envelope.newBuilder()
                .setTopic("/tasks/start")
                .setNamespace("nanofaas")
                .setTimestamp(Timestamp.newBuilder().setSeconds(1700000000))
                .setEvent(Any.pack(payload))
                .build();

        Event event = EventMapper.map(envelope);

        assertThat(event.topic()).isEqualTo("/tasks/start");
        assertThat(event.namespace()).isEqualTo("nanofaas");
        assertThat(event.taskEvent().containerId()).isEqualTo("abc");
        assertThat(event.taskEvent().pid()).isEqualTo(77);
        assertThat(event.taskEvent().exitStatus()).isNull();
    }

    @Test
    void decodesTaskDeletePayload() {
        var payload = containerd.events.TaskDelete.newBuilder()
                .setContainerId("abc").setPid(77).setExitStatus(3).build();
        var envelope = containerd.types.Envelope.newBuilder()
                .setTopic("/tasks/delete")
                .setEvent(Any.pack(payload))
                .build();

        Event event = EventMapper.map(envelope);

        assertThat(event.taskEvent().exitStatus()).isEqualTo(3);
    }

    @Test
    void unknownPayloadDecodesToNullTaskEvent() {
        var envelope = containerd.types.Envelope.newBuilder().setTopic("/snapshots/update").build();
        assertThat(EventMapper.map(envelope).taskEvent()).isNull();
    }
}
