package com.lianxibu.site.repository.sources;

import java.net.URI;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class JdbcSourceDayRunRegistrationsMySqlTest extends JdbcSourceDayRunRegistrationsTest {
    @Override DataSource dataSource(){
        String url=Objects.requireNonNull(System.getenv("B45_MYSQL_URL"));var endpoint=URI.create(url.substring(5));
        if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b45_inputs".equals(endpoint.getPath())||endpoint.getUserInfo()!=null)throw new IllegalStateException("dedicated fictional loopback database required");
        var data=new DriverManagerDataSource(url,"root","");var sql=new JdbcTemplate(data);
        for(String table:new String[]{"source_run_items","request_receipts","source_runs","sources"})sql.execute("DROP TABLE IF EXISTS "+table);return data;
    }
    @Override void ddl(String statement){
        for(String column:new String[]{"draft_config_json","effective_config_json","config_snapshot_json","coverage_json","job_refs_json","response_json","ack_json","result_ref_json"})statement=statement.replace(column+" CLOB",column+" JSON");
        sql.execute(statement.replace("CLOB","LONGTEXT").replace("TIMESTAMP(6)","DATETIME(6)"));
    }
}
