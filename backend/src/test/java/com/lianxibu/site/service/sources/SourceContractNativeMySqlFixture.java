package com.lianxibu.site.service.sources;

import java.net.URI;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** One owned source-contract schema per verifier JVM; never share partial job fixtures. */
final class SourceContractNativeMySqlFixture {
    private static DataSource current;
    private SourceContractNativeMySqlFixture() { }
    static synchronized DataSource mysql() {
        if (current != null) return current;
        var base = (DriverManagerDataSource) SourceRunReadServiceMySqlTest.mysql();
        String url = base.getUrl();
        var uri = URI.create(url.substring(5));
        if (uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalStateException("dedicated loopback fixture required");
        String name = "fictional_source_contract_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = base.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException("owned source-contract schema unavailable", failed);
        }
        current = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13336/" + name
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), "root", "");
        return current;
    }
}
