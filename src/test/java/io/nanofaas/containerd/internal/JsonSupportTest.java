package io.nanofaas.containerd.internal;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSupportTest {

    @Test
    void printsIntegralNumbersAsIntegersBecauseContainerdRejects1024Point0() {
        Struct struct = Struct.newBuilder()
                .putFields("limit", Value.newBuilder().setNumberValue(1024).build())
                .putFields("ratio", Value.newBuilder().setNumberValue(1.5).build())
                .build();

        assertThat(JsonSupport.print(struct)).contains("\"limit\":1024").contains("\"ratio\":1.5")
                .doesNotContain("1024.0");
    }

    @Test
    void whatItPrintsParsesBackToTheSameStruct() {
        Struct struct = Struct.newBuilder()
                .putFields("text", Value.newBuilder()
                        .setStringValue("quote\" back\\slash \b\f\n\r\t control\u0001").build())
                .putFields("flag", Value.newBuilder().setBoolValue(true).build())
                .putFields("nothing", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                .putFields("items", Value.newBuilder().setListValue(ListValue.newBuilder()
                        .addValues(Value.newBuilder().setNumberValue(1))
                        .addValues(Value.newBuilder().setStructValue(Struct.newBuilder()
                                .putFields("nested", Value.newBuilder().setStringValue("yes").build()))))
                        .build())
                .build();

        assertThat(JsonSupport.parse(JsonSupport.print(struct))).isEqualTo(struct);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> JsonSupport.parse("{not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
