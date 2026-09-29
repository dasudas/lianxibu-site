package com.lianxibu.site.domain.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Opaque browser token and CSRF value are separate random secrets in one HttpOnly cookie. */
public final class SessionSecrets {
    private static final Pattern COOKIE = Pattern.compile("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
    private SessionSecrets() { }

    public record Pair(String cookie, String csrf) { }

    public static Pair create(SecureRandom random) {
        byte[] token = new byte[32];
        byte[] csrf = new byte[32];
        random.nextBytes(token);
        random.nextBytes(csrf);
        String encodedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        String encodedCsrf = Base64.getUrlEncoder().withoutPadding().encodeToString(csrf);
        return new Pair(encodedToken + "." + encodedCsrf, encodedCsrf);
    }

    public static boolean validCookie(String cookie) {
        return cookie != null && COOKIE.matcher(cookie).matches();
    }

    public static String embeddedCsrf(String cookie) {
        return validCookie(cookie) ? cookie.substring(44) : null;
    }

    public static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    public static boolean sameDigest(byte[] expected, String actual) {
        return expected != null && actual != null
                && MessageDigest.isEqual(expected, digest(actual));
    }
}
