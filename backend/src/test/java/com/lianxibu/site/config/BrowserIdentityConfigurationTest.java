package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;

class BrowserIdentityConfigurationTest {
    @Test void rejectsUnsafeOriginAndAmbiguousRoleMaps() {
        var config=new BrowserIdentityConfiguration();
        var source=new JdbcDataSource();
        for(String origin:new String[]{"", "http://site.example.invalid", "https://user@site.example.invalid",
                "https://site.example.invalid/", "https://site.example.invalid?x=1", "https://site.example.invalid#part",
                "https://site.example.invalid:0", "https://site.example.invalid:99999"})
            assertThrows(IllegalStateException.class,()->config.browserSessions(source,origin,"fictional-owner",""));
        for(String owner:new String[]{"", "same,same", " owner", "owner,", "bad/id"})
            assertThrows(IllegalStateException.class,()->config.browserSessions(source,"https://site.example.invalid",owner,""));
        assertThrows(IllegalStateException.class,()->config.browserSessions(source,"https://site.example.invalid","same","same"));
        assertEquals("https://site.example.invalid",BrowserIdentityConfiguration.origin("https://site.example.invalid"));
    }
}
