package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkAttachmentTest {

    @Test
    void thePrimaryAddressIsTheFirstOneWithoutItsPrefix() {
        var attachment = new NetworkAttachment(List.of("10.4.0.7/24", "fd00::7/64"), List.of(), List.of(), List.of(), null);
        assertThat(attachment.primaryAddress()).isEqualTo("10.4.0.7");
        assertThat(new NetworkAttachment(List.of("10.4.0.7"), List.of(), List.of(), List.of(), null).primaryAddress())
                .isEqualTo("10.4.0.7");
        assertThat(NetworkAttachment.EMPTY.primaryAddress()).isNull();
    }

    @Test
    void rendersResolvConfOnlyForTheDnsItHas() {
        assertThat(NetworkAttachment.EMPTY.toResolvConf()).isEmpty();
        var dns = new NetworkAttachment(List.of(), List.of(), List.of("10.4.0.1", "1.1.1.1"),
                List.of("svc.local", "local"), "svc.local");
        assertThat(dns.toResolvConf()).isEqualTo("""
                nameserver 10.4.0.1
                nameserver 1.1.1.1
                domain svc.local
                search svc.local local
                """);
        assertThat(new NetworkAttachment(List.of(), List.of(), List.of(), List.of(), "only.domain").toResolvConf())
                .isEqualTo("domain only.domain\n");
    }
}
