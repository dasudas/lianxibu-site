package com.lianxibu.site.service.identity;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id only; legacy hashes are never interpreted or upgraded on login. */
public final class Argon2Passwords implements BrowserSessionService.PasswordVerifier {
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = encoder.encode("fictional-unavailable-account-dummy");

    public String encode(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 1024) {
            throw new IllegalArgumentException("password length outside policy");
        }
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String encodedHash) {
        return encodedHash != null && encodedHash.startsWith("$argon2id$")
                && encoder.matches(password, encodedHash);
    }

    @Override
    public String dummyHash() { return dummyHash; }
}
