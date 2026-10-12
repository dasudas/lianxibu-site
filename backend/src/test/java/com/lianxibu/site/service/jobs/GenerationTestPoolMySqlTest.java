package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class GenerationTestPoolMySqlTest {
    @Test void thousandsOfStatementsReuseBoundedRealMysqlConnectionsAcrossFixtureInstances() {
        var pool=GenerationJdbcStoreMySqlTest.isolatedSource(System.getenv("B45_MYSQL_URL"));
        assertSame(pool,GenerationJdbcStoreMySqlTest.isolatedSource(System.getenv("B45_MYSQL_URL")));
        var jdbc=new JdbcTemplate(pool);assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));
        var connections=new HashSet<Long>();
        for(int n=0;n<3000;n++)connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
        assertTrue(connections.size()<=4);assertTrue(pool.getHikariPoolMXBean().getTotalConnections()<=4);
        assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
    }
    @Test void wrongHostPortAndSchemaAreRefusedBeforeOpeningAnyConnection() {
        for(String url:new String[]{"jdbc:mysql://example.invalid:13336/fictional_b45_inputs","jdbc:mysql://127.0.0.1:3306/fictional_b45_inputs","jdbc:mysql://127.0.0.1:13336/fictional_other_inputs",null})
            assertThrows(IllegalStateException.class,()->GenerationJdbcStoreMySqlTest.isolatedSource(url));
    }
    @Test void borrowedSpringContextsCannotCloseTheSharedJvmOwnedPool() {
        var pool=GenerationJdbcStoreMySqlTest.isolatedSource(System.getenv("B45_MYSQL_URL"));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withBean(javax.sql.DataSource.class,()->pool)
                .run(context->assertSame(pool,context.getBean(javax.sql.DataSource.class)));
        assertFalse(pool.isClosed());assertEquals("8.0.36",new JdbcTemplate(pool).queryForObject("SELECT VERSION()",String.class));
    }
}
