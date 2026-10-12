package com.lianxibu.site.service.sources;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerRunCreateServiceMySqlTest extends OwnerRunCreateServiceTest {
    @Override SourceRunLeaseServiceTest fixture(){return mysqlFixture();}
    static SourceRunLeaseServiceTest mysqlFixture(){return new SourceRunLeaseServiceTest(){
        @Override javax.sql.DataSource source(){
            var ds=SourceRunReadServiceMySqlTest.mysql();var j=new org.springframework.jdbc.core.JdbcTemplate(ds);
            for(String table:java.util.List.of("source_run_items","source_runs","sources","request_receipts","worker_lock"))j.execute("DROP TABLE IF EXISTS "+table);
            return ds;
        }
        @Override void ddl(String sql){
            for(String field:java.util.List.of("config_json","draft_config_json","effective_config_json","config_snapshot_json","coverage_json","job_refs_json","response_json","ack_json","result_ref_json","result_json"))sql=sql.replace(field+" CLOB",field+" JSON");
            jdbc.execute(sql.replace("CLOB","LONGTEXT").replace("TIMESTAMP","DATETIME(6)"));
        }
    };}
}
