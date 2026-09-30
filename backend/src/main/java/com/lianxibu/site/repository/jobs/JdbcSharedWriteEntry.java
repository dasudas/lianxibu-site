package com.lianxibu.site.repository.jobs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Acquires the sole shared writer entry row without changing AI slot state. */
public final class JdbcSharedWriteEntry {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcSharedWriteEntry(DataSource source) {
        if (source == null) throw new IllegalArgumentException("shared entry data source required");
        this.source = source;
        jdbc = new JdbcTemplate(source);
    }

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("shared entry requires an active transaction");
        }
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            if (connection.getMetaData().getDatabaseProductName().contains("MySQL")) {
                lockMySql();
            } else {
                lockRow();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("shared entry connection unavailable", error);
        }
    }

    private void lockMySql() {
        Integer previous = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
        if (previous == null) throw new IllegalStateException("lock wait timeout unavailable");
        try {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
            lockRow();
        } finally {
            // Values are read from this same connection, never from user input.
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
        }
    }

    private void lockRow() {
        List<Integer> rows = jdbc.query("SELECT id FROM worker_lock FOR UPDATE",
                (rs, ignored) -> rs.getInt(1));
        if (rows.size() != 1 || rows.getFirst() != 1) {
            throw new IllegalStateException("shared entry row is not unique");
        }
    }
}
