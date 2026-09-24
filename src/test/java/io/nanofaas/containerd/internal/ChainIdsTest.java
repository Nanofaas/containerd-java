package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainIdsTest {

    private static String sha256(String s) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void singleLayerChainIdIsTheDiffId() {
        assertThat(ChainIds.chainId(List.of("sha256:layer-a"))).isEqualTo("sha256:layer-a");
    }

    @Test
    void chainIdChainsLayersWithSpaceSeparator() {
        String diff1 = "sha256:d1";
        String diff2 = "sha256:d2";
        String expected = sha256(diff1 + " " + diff2);
        assertThat(ChainIds.chainId(List.of(diff1, diff2))).isEqualTo(expected);
    }

    @Test
    void threeLayersChainRecursively() {
        String diff1 = "sha256:d1";
        String diff2 = "sha256:d2";
        String diff3 = "sha256:d3";
        String expected = sha256(sha256(diff1 + " " + diff2) + " " + diff3);
        assertThat(ChainIds.chainId(List.of(diff1, diff2, diff3))).isEqualTo(expected);
    }

    @Test
    void emptyLayerListHasEmptyChainId() {
        assertThat(ChainIds.chainId(List.of())).isEmpty();
    }
}
