package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class GenerationBusinessDateMySqlTest extends GenerationBusinessDateTest {
    @Override boolean nativeMySql(){return true;}
    @Override @BeforeEach void fixture(){
        var f=new GenerationJdbcStoreMySqlTest();f.fixture();source=f.source;jdbc=f.jdbc;
        writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source),()->25,n->{});
        store=new GenerationJdbcStore(source,clock,"fictional-owner",OWNER,command(false));
    }
    @Override void dropReceiptConstraint(){jdbc.execute("ALTER TABLE jobs DROP CHECK fictional_receipt_failure");}
}
