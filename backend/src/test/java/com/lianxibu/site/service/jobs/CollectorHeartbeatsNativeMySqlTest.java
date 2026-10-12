package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectorHeartbeatsNativeMySqlTest extends CollectorHeartbeatsNativeTest {
 @Override javax.sql.DataSource source(){String url=System.getenv("B45_MYSQL_URL");if(url==null)throw new IllegalStateException("fictional database required");var uri=java.net.URI.create(url.substring(5));if(!"mysql".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()!=13336||!"/fictional_b45_inputs".equals(uri.getPath()))throw new IllegalStateException("dedicated loopback database required");return new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"root","");}
}
