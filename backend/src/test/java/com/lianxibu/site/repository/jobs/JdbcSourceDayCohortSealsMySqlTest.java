package com.lianxibu.site.repository.jobs;

import java.net.URI;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class JdbcSourceDayCohortSealsMySqlTest extends JdbcSourceDayCohortSealsTest {
    @Override DataSource dataSource(){
        String url=Objects.requireNonNull(System.getenv("B44_MYSQL_URL"));var endpoint=URI.create(url.substring(5));
        if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b44_results".equals(endpoint.getPath())||endpoint.getUserInfo()!=null)throw new IllegalStateException("dedicated fictional loopback database required");
        var data=new DriverManagerDataSource(url,"root","");new JdbcTemplate(data).execute("DROP TABLE IF EXISTS jobs");return data;
    }
    @Override void ddl(String statement){
        for(String column:new String[]{"request_json","payload_json","target_key_json","action_payload"})statement=statement.replace(column+" CLOB",column+" JSON");
        sql.execute(statement.replace("CLOB","LONGTEXT").replace("TIMESTAMP(6)","DATETIME(6)"));
    }
}
