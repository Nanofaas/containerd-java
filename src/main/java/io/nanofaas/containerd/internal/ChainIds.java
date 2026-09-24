package io.nanofaas.containerd.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** ChainID computation for OCI layer diff IDs (verified against opencontainers/image-spec). */
public final class ChainIds {

    private ChainIds() {
    }

    /**
     * chainID[0] = diffID[0]; chainID[i] = sha256(chainID[i-1] + " " + diffID[i]).
     * Empty input yields the empty string (scratch images).
     */
    public static String chainId(List<String> diffIds) {
        if (diffIds.isEmpty()) {
            return "";
        }
        String current = diffIds.get(0);
        for (int i = 1; i < diffIds.size(); i++) {
            current = "sha256:" + sha256Hex(current + " " + diffIds.get(i));
        }
        return current;
    }

    /** Lowercase hex SHA-256 of the string's UTF-8 bytes. */
    static String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
