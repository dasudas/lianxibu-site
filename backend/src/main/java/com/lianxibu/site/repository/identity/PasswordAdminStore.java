package com.lianxibu.site.repository.identity;

/** Host-only account maintenance; never exposed through a web controller. */
public interface PasswordAdminStore {
    void initialize(String userId, String username, String encodedHash);
    void reset(String username, String encodedHash);
}
