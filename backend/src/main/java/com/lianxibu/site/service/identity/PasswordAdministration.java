package com.lianxibu.site.service.identity;

import com.lianxibu.site.repository.identity.PasswordAdminStore;

/** Explicit host action: encode a new password, then let the store commit it atomically. */
public final class PasswordAdministration {
    private final PasswordAdminStore store;
    private final Argon2Passwords passwords;

    public PasswordAdministration(PasswordAdminStore store, Argon2Passwords passwords) {
        if (store == null || passwords == null) throw new IllegalArgumentException("password admin dependencies required");
        this.store = store;
        this.passwords = passwords;
    }

    public void initialize(String userId, String username, String password) {
        identity(userId);
        identity(username);
        store.initialize(userId, username, passwords.encode(password));
    }

    public void reset(String username, String password) {
        identity(username);
        store.reset(username, passwords.encode(password));
    }

    private static void identity(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 64
                || value.codePoints().anyMatch(cp -> cp < 32 || cp == 127)) {
            throw new IllegalArgumentException("invalid account identity");
        }
    }
}
