package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class JdbcSourceDayRosterMySqlTest {
    private static final Instant NOW=Instant.parse("2026-09-20T16:10:00.000123Z");
    private DataSource data;private JdbcTemplate sql;
    @BeforeEach void fixture(){
        String url=Objects.requireNonNull(System.getenv("B45_MYSQL_URL"));var uri=URI.create(url.substring(5));
        if(!"mysql".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()!=13336||!"/fictional_b45_inputs".equals(uri.getPath()))throw new IllegalStateException("dedicated fictional database required");
        data=new DriverManagerDataSource(url,"root","");sql=new JdbcTemplate(data);
        sql.execute("DROP TABLE IF EXISTS sources");
        sql.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(32),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(32),state VARCHAR(32),config_valid_until DATETIME(6),draft_config_json JSON,effective_config_json JSON)");
    }
    private TransactionTemplate reads(DataSource source){
        var manager=new DataSourceTransactionManager(source);
        var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx;
    }
    private SourceDayRoster.Evidence read(){return reads(data).execute(s->new JdbcSourceDayRoster(data,Clock.fixed(NOW,ZoneOffset.UTC)).read());}
    private void insert(String id,String category,String body,Instant valid){
        sql.update(c->{var ps=c.prepareStatement("INSERT INTO sources VALUES(?,?,2,3,'draft','disabled',?,NULL,?)");
            ps.setString(1,id);ps.setString(2,category);ps.setTimestamp(3,valid==null?null:Timestamp.from(valid),Calendar.getInstance(TimeZone.getTimeZone("UTC")));ps.setString(4,body);return ps;});
    }
    @Test void realJsonRepresentationAndUtcMicrosecondsRemainExactUnderNonUtcJvm() throws Exception {
        var previous=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));insert("fictional-a","agent","{\"z\":\"🟣\",\"a\":1}",NOW.plusSeconds(9));
            insert("fictional-b",null,null,null);insert("fictional-c",null,null,null);insert("fictional-d",null,null,null);
            String actual=sql.queryForObject("SELECT effective_config_json FROM sources WHERE id='fictional-a'",String.class);
            var value=reads(data).execute(s->{
                assertEquals("REPEATABLE-READ",sql.queryForObject("SELECT @@transaction_isolation",String.class));
                assertEquals(1,sql.queryForObject("SELECT @@transaction_read_only",Integer.class));
                return new JdbcSourceDayRoster(data,Clock.fixed(NOW,ZoneOffset.UTC)).read();
            });assertNotNull(value);assertEquals(4,value.sourceCount());assertEquals(3,value.sources().stream().filter(s->s.category()==null).count());
            var a=value.sources().getFirst();assertEquals(NOW.plusSeconds(9),a.configValidUntil());assertEquals(NOW,value.capturedAt());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8))),a.effectiveConfigHash());
            assertFalse(value.metadataJson().contains("🟣"));assertEquals("disabled",a.legacyState());assertTrue(a.effectivePresent());assertFalse(a.draftPresent());
        }finally{TimeZone.setDefault(previous);}
    }
    @Test void actualRepeatableReadKeepsAggregateAndRowsInOriginalSnapshot() {
        insert("fictional-original",null,null,null);var mutation=new AtomicBoolean();
        DataSource wrapped=new AbstractDataSource(){
            @Override public Connection getConnection() throws SQLException{return intercept(data.getConnection());}
            @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("unsupported fixture credentials");}
            private Connection intercept(Connection connection){
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    Object result=invoke(connection,method,args);
                    if(method.getName().equals("createStatement")){
                        var statement=(Statement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                            Object returned=invoke(statement,m,a);
                            if(m.getName().equals("executeQuery")&&a[0].toString().contains("COUNT(*)")&&mutation.compareAndSet(false,true)){
                                sql.update("UPDATE sources SET revision=9");insert("fictional-new",null,null,null);
                            }
                            return returned;
                        });
                    }
                    return result;
                });
            }
        };
        var original=reads(wrapped).execute(s->new JdbcSourceDayRoster(wrapped,Clock.fixed(NOW,ZoneOffset.UTC)).read());
        assertTrue(mutation.get());assertNotNull(original);assertEquals(1,original.sourceCount());assertEquals(2,original.sources().getFirst().sourceRevision());
        var later=read();assertEquals(2,later.sourceCount());assertEquals(9,later.sources().stream().filter(s->s.id().equals("fictional-original")).findFirst().orElseThrow().sourceRevision());
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}
    }
    @Test void actualPerConfigAggregateAndCountCapacityRejectRatherThanTruncate() {
        insert("fictional-large",null,"\""+"x".repeat(65535)+"\"",null);assertThrows(SourceDayRoster.CapacityExceeded.class,this::read);
        sql.update("DELETE FROM sources");String body="\""+"x".repeat(65534)+"\"";
        sql.batchUpdate("INSERT INTO sources VALUES(?,NULL,0,0,'draft','disabled',NULL,NULL,?)",new org.springframework.jdbc.core.BatchPreparedStatementSetter(){
            public int getBatchSize(){return 17;}public void setValues(PreparedStatement ps,int i)throws SQLException{ps.setString(1,"fictional-"+i);ps.setString(2,body);}
        });assertThrows(SourceDayRoster.CapacityExceeded.class,this::read);
        sql.update("DELETE FROM sources");
        sql.batchUpdate("INSERT INTO sources VALUES(?,NULL,0,0,'draft','disabled',NULL,NULL,NULL)",new org.springframework.jdbc.core.BatchPreparedStatementSetter(){
            public int getBatchSize(){return 10001;}public void setValues(PreparedStatement ps,int i)throws SQLException{ps.setString(1,"fictional-"+i);}
        });assertThrows(SourceDayRoster.CapacityExceeded.class,this::read);assertEquals(10001,sql.queryForObject("SELECT COUNT(*) FROM sources",Integer.class));
    }
    @Test void actualEmptyRowsRemainDifferentFromInvalidMetadataOrSqlFailure() {
        var empty=read();assertNotNull(empty);assertEquals(0,empty.sourceCount());
        insert("fictional",null,null,null);sql.update("UPDATE sources SET config_revision=NULL");assertNull(read());
        sql.update("UPDATE sources SET config_revision=0,category='fictional_unknown'");assertNull(read());
        sql.execute("DROP TABLE sources");assertNull(read());
    }
}
