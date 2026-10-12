package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultFixturePoolMySqlTest {
    @Test void wrongEndpointIsRejectedBeforeAnyConnectionCanBeOpened(){
        for(String url:List.of("jdbc:mysql://203.0.113.30:13336/fictional_b44_results","jdbc:mysql://127.0.0.1:3306/fictional_b44_results","jdbc:mysql://127.0.0.1:13336/fictional_wrong_results","jdbc:mysql://fictional-user@127.0.0.1:13336/fictional_b44_results","jdbc:fictional-invalid","not-a-jdbc-url"))assertThrows(RuntimeException.class,()->ResultJdbcMySqlTest.isolatedSource(url));
    }
    @Test void coldBusinessFixtureResetsKeepTheSameBoundedActualConnectionOwner(){
        var first=new ResultJdbcMySqlTest();first.fixture();first.service.applyAutomatic(first.store.input(),first.raw(),first.action("fictional-b44-cold-reset"));assertEquals(1,first.count("contents"));
        var second=new ResultJdbcMySqlTest();second.fixture();assertSame(first.source,second.source);assertEquals(0,second.count("contents"));assertEquals(5,((HikariDataSource)second.source).getMaximumPoolSize());
        second.service.applyAutomatic(second.store.input(),second.raw(),second.action("fictional-b44-cold-reset-again"));assertEquals(1,second.count("contents"));assertEquals(1,second.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='result_application'",Integer.class));
    }
    @Test void endingAPooledBorrowCannotCloseTheNativePoolNeededByAnotherFixture(){
        var borrowed=new PooledResultJdbcMySqlFixture();borrowed.fixture();var source=borrowed.source;borrowed.close();((HikariDataSource)source).close();assertEquals(1,new JdbcTemplate(source).queryForObject("SELECT 1",Integer.class));
        var next=new ResultJdbcMySqlTest();next.fixture();assertSame(source,next.source);assertEquals("8.0.36",next.jdbc.queryForObject("SELECT VERSION()",String.class));assertEquals("READ-COMMITTED",next.jdbc.queryForObject("SELECT @@transaction_isolation",String.class));assertEquals(0,next.count("contents"));
    }
}
