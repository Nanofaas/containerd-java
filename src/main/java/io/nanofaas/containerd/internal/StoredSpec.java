package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads back the OCI runtime spec containerd stores on a container.
 *
 * <p>The spec travels as JSON inside an {@code Any}, so what was sent at create time can be read
 * again at any point without recomputing it from the image. That is what lets an exec inherit the
 * container's environment without re-resolving the image's manifest and config blobs.
 *
 * @param env the container process's environment as {@code KEY=VALUE} entries
 * @param workingDir the container process's working directory, {@code null} if unset
 * @param rlimits the container process's {@code rlimits} array exactly as it was stored, or
 *        {@code null} if the spec carries none. Kept as the raw value rather than unpacked into
 *        fields so that every limit the container has travels to an exec, including ones this
 *        library does not yet know how to set.
 */
record StoredSpec(List<String> env, String workingDir, Value rlimits) {

    static final StoredSpec EMPTY = new StoredSpec(List.of(), null, null);

    StoredSpec {
        env = List.copyOf(env);
    }

    /** Parses the stored spec, yielding {@link #EMPTY} for anything it cannot read. */
    static StoredSpec parse(Any spec) {
        if (spec == null || spec.getValue().isEmpty()) {
            return EMPTY;
        }
        Struct root;
        try {
            root = JsonSupport.parse(spec.getValue().toString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return EMPTY; // a spec this client did not write, or a shape it does not know
        }
        Struct process = root.getFieldsOrDefault("process", Value.getDefaultInstance()).getStructValue();
        if (process.getFieldsCount() == 0) {
            return EMPTY;
        }
        List<String> env = new ArrayList<>();
        Value envValue = process.getFieldsOrDefault("env", Value.getDefaultInstance());
        if (envValue.getKindCase() == Value.KindCase.LIST_VALUE) {
            for (Value entry : envValue.getListValue().getValuesList()) {
                env.add(entry.getStringValue());
            }
        }
        String cwd = process.getFieldsOrDefault("cwd", Value.getDefaultInstance()).getStringValue();
        Value rlimits = process.getFieldsOrDefault("rlimits", Value.getDefaultInstance());
        return new StoredSpec(env, cwd.isEmpty() ? null : cwd,
                rlimits.getKindCase() == Value.KindCase.LIST_VALUE ? rlimits : null);
    }
}
