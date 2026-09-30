package com.lianxibu.site.service.sources;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Runtime-key encryption for same-claim ACK replay; plaintext never enters the database. */
public final class RunLeaseTokenCodec {
    public record Issued(String token, byte[] digest, byte[] ciphertext, String keyId) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random;

    public RunLeaseTokenCodec(SecretKey key, String keyId, SecureRandom random) {
        if (key == null || key.getEncoded() == null || key.getEncoded().length != 32
                || keyId == null || keyId.isBlank() || keyId.length() > 64 || random == null) {
            throw new IllegalArgumentException("runtime claim key required");
        }
        this.key = key;
        this.keyId = keyId;
        this.random = random;
    }

    public Issued issue(String runId, String nodeId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(runId, nodeId));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));
            return new Issued(token, digest(token), ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array(), keyId);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("claim encryption unavailable", error);
        } finally {
            java.util.Arrays.fill(raw, (byte) 0);
        }
    }

    public String reopen(String runId, String nodeId, String storedKeyId, byte[] ciphertext,
                         byte[] expectedDigest) {
        if (!keyId.equals(storedKeyId) || ciphertext == null || ciphertext.length < 29
                || expectedDigest == null || expectedDigest.length != 32) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, ciphertext, 0, 12));
            cipher.updateAAD(aad(runId, nodeId));
            String token = new String(cipher.doFinal(ciphertext, 12, ciphertext.length - 12),
                    StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expectedDigest, digest(token)) ? token : null;
        } catch (GeneralSecurityException error) {
            return null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public static byte[] digest(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("invalid claim token");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] aad(String runId, String nodeId) {
        return (runId + "\n" + nodeId).getBytes(StandardCharsets.UTF_8);
    }
}
