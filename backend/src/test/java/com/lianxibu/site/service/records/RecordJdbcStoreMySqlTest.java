package com.lianxibu.site.service.records;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the original store assertions against a guarded disposable native fixture. */
@EnabledIfEnvironmentVariable(named="V03_MYSQL",matches="true")
class RecordJdbcStoreMySqlTest extends RecordJdbcStoreTest {
    private HikariDataSource pool;
    @Override @BeforeEach void setup() {
        try {
            String base=System.getenv("V03_MYSQL_URL");
            if(base==null||!base.startsWith("jdbc:"))throw new IllegalStateException();
            URI uri=URI.create(base.substring(5));
            if(!"mysql".equals(uri.getScheme())||uri.getPort()!=13336||uri.getHost()==null
                    ||!InetAddress.getByName(uri.getHost()).isLoopbackAddress()
                    ||!"/fictional_v03_shared".equals(uri.getPath()))throw new IllegalStateException();
            String user=System.getenv().getOrDefault("V03_MYSQL_USER","root");
            String password=System.getenv().getOrDefault("V03_MYSQL_PASSWORD","");
            var bootstrap=new JdbcTemplate(new DriverManagerDataSource(base,user,password));
            assertEquals("8.0.36",bootstrap.queryForObject("SELECT VERSION()",String.class));
            bootstrap.execute("CREATE DATABASE IF NOT EXISTS fictional_records CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            pool=new HikariDataSource();pool.setJdbcUrl(base.replace("/fictional_v03_shared","/fictional_records"));
            pool.setUsername(user);pool.setPassword(password);pool.setMaximumPoolSize(2);pool.setMinimumIdle(0);
            ds=pool;jdbc=new JdbcTemplate(ds);
            jdbc.execute("DROP TABLE IF EXISTS request_receipts");jdbc.execute("DROP TABLE IF EXISTS user_records");
            createTables(true);
        } catch (Exception failed) { throw new IllegalStateException("guarded native record fixture unavailable",failed); }
    }
    @AfterEach void closeNativePool() { if(pool!=null)pool.close(); }
}
