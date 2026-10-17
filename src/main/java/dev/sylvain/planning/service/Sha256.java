package dev.sylvain.planning.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The SHA-256 a secret is stored and looked up as — never the secret itself. */
public final class Sha256 {

    private Sha256() {}

    /** The SHA-256 of {@code value}'s UTF-8 bytes, in lower-case hex. */
    public static String hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
