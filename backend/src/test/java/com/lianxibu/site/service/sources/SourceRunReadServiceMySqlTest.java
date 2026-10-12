package com.lianxibu.site.service.sources;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceRunReadServiceMySqlTest extends SourceRunReadServiceTest {
    @Override javax.sql.DataSource source() { return mysql(); }
    static javax.sql.DataSource mysql() {
        String url=System.getenv("B45_MYSQL_URL");if(url==null)throw new IllegalStateException("fictional database required");
        var uri=java.net.URI.create(url.substring(5));
        if(!"mysql".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()!=13336||!"/fictional_b45_inputs".equals(uri.getPath()))throw new IllegalStateException("dedicated loopback database required");
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"root","");
    }
}
