package com.lianxibu.site.domain.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Derives replayable opaque node credentials without storing recoverable tokens in MySQL. */
public final class NodeTokens {
    private final byte[] secret;

    public NodeTokens(byte[] secret) {
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("node key too short");
        this.secret = secret.clone();
    }

    public String issue(String actorId, String scope, String key, String nodeId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            // Length prefixes prevent ambiguity when identities contain separators.
            for (String part : new String[] {"collector-node-v1", actorId, scope, key, nodeId}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update((byte) (bytes.length >>> 24));
                mac.update((byte) (bytes.length >>> 16));
                mac.update((byte) (bytes.length >>> 8));
                mac.update((byte) bytes.length);
                mac.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception error) {
            throw new IllegalStateException("node token derivation unavailable", error);
        }
    }

    public static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static boolean valid(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }
}
