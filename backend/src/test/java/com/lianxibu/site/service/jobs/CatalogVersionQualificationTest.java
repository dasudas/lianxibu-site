package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.CatalogVersionQualificationService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Owned native old/new source evidence and original worker/result. External sender remains fictional. */
class CatalogVersionQualificationTest {
    CatalogPreviousVersionProfileTest.Fixture f;CatalogVersionQualificationService service;ObjectNode current;boolean mysql(){return false;}
    @BeforeEach void setup(){initialize("agent",false);}
    CatalogPreviousVersionProfileTest.Fixture fixture(){return new CatalogPreviousVersionProfileTest.Fixture();}
    void initialize(String category,boolean unknown){
        f=fixture();f.unknown=unknown;f.initializeCatalog(mysql(),category,true);current=f.catalogPayload();
        if(!unknown){if(category.equals("skill"))((ObjectNode)current.get("evidence").get(0)).put("file_hash","d".repeat(64));else{current.put("version","v2");((ObjectNode)current.get("evidence").get(0)).put("revision","v2");}}
        f.jdbc.update("INSERT INTO source_run_items VALUES('fictional-current-item','fictional-run','fictional-source',3,?,?,'accepted','pending','initialized',1,?,?,NULL,'fictional-current-source-item')",category,java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)),JdbcFrozenInputs.canonical(current),HexFormat.of().parseHex(CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(current))));
        service=new CatalogVersionQualificationService(f.source,f.tx);
    }
    Optional<CatalogVersionQualificationService.Selection> read(){return service.read(f.category,"fictional-entity","fictional-analysis","fictional-current-item",f.clock.instant());}
    void update(){f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=?,revision=revision+1 WHERE id='fictional-current-item'",JdbcFrozenInputs.canonical(current),HexFormat.of().parseHex(CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(current))));}
    @Test void FourCategoryActualCurrentEvidenceBindsOriginalProofAndNativeConfiguration(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var value=read().orElseThrow();assertTrue(value.versionChanged());assertEquals("fictional-current-item",value.member().id());assertEquals(3,value.member().revision());
            var proof=CanonicalJson.parse(value.currentProofJson());assertEquals("fictional-source",proof.get("source_id").asText());assertEquals(1,proof.get("config_version").asLong());
            assertEquals(value.currentProofHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,proof));assertEquals(f.identity.inputHash(),value.previous().evidence().previous().inputHash());assertEquals(1,f.sends);assertFalse(value.toString().contains("fictional"));
        }
    }
    @Test void SameVersionMetadataChangeIsKnownFalseButMissingOldOrCurrentVersionIsUnknown(){
        current=f.catalogPayload();current.put("summary","Fictional changed summary");update();assertFalse(read().orElseThrow().versionChanged());
        current.putNull("version");update();assertTrue(read().isEmpty());initialize("agent",true);assertTrue(read().isEmpty());
        assertTrue(service.read("agent","fictional-entity","fictional-analysis","fictional-absent-item",f.clock.instant()).isEmpty());assertEquals(1,f.sends);
    }
    @Test void CurrentHashConfigurationAliasActivationAndFutureTimeCannotBecomeVersionEvidence(){
        f.jdbc.update("UPDATE source_run_items SET evidence_hash=? WHERE id='fictional-current-item'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        initialize("agent",false);f.jdbc.update("UPDATE source_runs SET config_version=2");assertThrows(RuntimeException.class,this::read);
        initialize("agent",false);f.jdbc.update("UPDATE source_run_items SET source_id='fictional-foreign' WHERE id='fictional-current-item'");assertThrows(RuntimeException.class,this::read);
        initialize("agent",false);f.jdbc.update("UPDATE source_runs SET activation_stage='draft'");assertThrows(RuntimeException.class,this::read);
        initialize("agent",false);f.jdbc.update("UPDATE source_runs SET finished_at=?",java.sql.Timestamp.from(f.clock.instant().plusSeconds(1)));assertThrows(RuntimeException.class,this::read);assertEquals(1,f.sends);
    }
    @Test void ForeignCurrentIdentityUnknownLegacyAndWrongOuterTransactionsFailClosed(){
        ((ObjectNode)current.get("identity")).put("key","fictional-foreign");update();assertThrows(RuntimeException.class,this::read);
        initialize("agent",false);f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=1");assertTrue(read().isEmpty());
        var tx=new TransactionTemplate(f.tx);assertThrows(RuntimeException.class,()->tx.execute(s->read()));
        assertThrows(RuntimeException.class,()->service.read("news_tech","fictional-entity","fictional-analysis","fictional-current-item",f.clock.instant()));
        assertThrows(RuntimeException.class,()->service.read("agent","fictional-entity","fictional-analysis","fictional-current-item",f.clock.instant().plusNanos(1)));assertEquals(1,f.sends);
    }
    void OneRepeatableReadSnapshotKeepsOldEntityAndCurrentMemberConsistentAcrossIndependentWriter()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean();
        DataSource wrapped=blockingSource(f.source,entered,release,once);var isolated=new CatalogVersionQualificationService(wrapped,new DataSourceTransactionManager(wrapped));
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->isolated.read("agent","fictional-entity","fictional-analysis","fictional-current-item",f.clock.instant()).orElseThrow());
            assertTrue(entered.await(10,TimeUnit.SECONDS));try{f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1,analysis_generation=2,analysis_state='failed'");current.put("version","v3");((ObjectNode)current.get("evidence").get(0)).put("revision","v3");update();}finally{release.countDown();}
            var captured=future.get(10,TimeUnit.SECONDS);assertEquals(1,captured.previous().evidence().previous().entityGeneration());assertEquals(3,captured.member().revision());assertEquals("v2",CanonicalJson.parse(captured.current().json()).get("version").get("version").asText());
            var next=read().orElseThrow();assertEquals(2,next.previous().evidence().previous().entityGeneration());assertEquals(4,next.member().revision());assertNotEquals(captured.currentProofHash(),next.currentProofHash());assertEquals(1,f.sends);
        }finally{release.countDown();}
    }
    @Test void BorrowedOriginalReaderRequiresReadonlyRepeatableReadAndHasNoImplicitNewTransaction(){
        var original=new CatalogPreviousAnalysisService(f.source,f.tx);assertThrows(RuntimeException.class,()->original.readInSnapshot("agent","fictional-entity","fictional-analysis"));
        var wrong=new TransactionTemplate(f.tx);wrong.setReadOnly(true);wrong.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->wrong.execute(s->original.readInSnapshot("agent","fictional-entity","fictional-analysis")));
        var correct=new TransactionTemplate(f.tx);correct.setReadOnly(true);correct.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);assertTrue(correct.execute(s->original.readInSnapshot("agent","fictional-entity","fictional-analysis")).isPresent());
    }
    static DataSource blockingSource(DataSource source,CountDownLatch entered,CountDownLatch release,java.util.concurrent.atomic.AtomicBoolean once){
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            var result=invoke(source,method,args);if(!(result instanceof java.sql.Connection connection))return result;
            return Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(cp,cm,ca)->{
                var statement=invoke(connection,cm,ca);if(!(statement instanceof java.sql.PreparedStatement prepared)||ca==null||!(ca[0] instanceof String sql)||!sql.contains("FROM source_run_items i JOIN source_runs r"))return statement;
                return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(sp,sm,sa)->{if(sm.getName().equals("executeQuery")&&once.compareAndSet(false,true)){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fictional writer timeout");}return invoke(prepared,sm,sa);});
            });
        });
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException cause){throw cause.getCause();}}
}
