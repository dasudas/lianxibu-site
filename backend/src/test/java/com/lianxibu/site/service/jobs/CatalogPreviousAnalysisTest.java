package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Real frozen INPUT, worker authorization, retained result and analysis application; only the external sender is fictional. */
class CatalogPreviousAnalysisTest {
    Fixture f;CatalogPreviousAnalysisService service;boolean mysql(){return false;}
    @BeforeEach void setup(){initialize("agent",true);}
    void initialize(String category,boolean apply){f=new Fixture();f.initializeCatalog(mysql(),category,apply);service=new CatalogPreviousAnalysisService(f.source,f.tx);}
    Optional<CatalogPreviousAnalysisService.Previous> read(){return service.read(f.category,"fictional-entity","fictional-analysis");}
    @Test void FourCategoriesReadActualOriginalChainAndPreserveNewerFailedGeneration(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,true);var before=read().orElseThrow();assertEquals(category,before.category());assertEquals(1,before.generation());
            assertEquals(f.identity.inputHash(),before.inputHash());assertEquals(f.clock.instant(),before.appliedAt());
            assertEquals(CanonicalJson.parse(f.raw()).get("payload"),CanonicalJson.parse(before.resultJson()));
            assertEquals(CanonicalJson.parse(new JdbcFrozenInputs(f.source).read(Fixture.ROOT,f.identity.inputHash())),CanonicalJson.parse(before.inputJson()));
            f.jdbc.update("UPDATE analyzed_entities SET analysis_generation=2,analysis_state='failed',revision=revision+1");
            var old=read().orElseThrow();assertEquals(1,old.generation());assertEquals(2,old.entityGeneration());assertEquals(before.inputHash(),old.inputHash());
            assertEquals(1,f.sends);assertFalse(old.toString().contains("fictional"));assertEquals(1,new JdbcJobRecords(f.source).byId(Fixture.ROOT).callCount());
        }
    }
    @Test void MissingLegacyAndNoOriginalEvidenceRemainUnknownAndDoNotGrantFalse(){
        assertTrue(service.read("agent","fictional-absent","fictional-analysis").isEmpty());
        f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=1");assertTrue(read().isEmpty());
        f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=0");f.jdbc.update("UPDATE contents SET input_job_id=NULL");assertTrue(read().isEmpty());
        initialize("agent",false);assertTrue(read().isEmpty());assertEquals(1,f.sends);assertEquals(0,f.count("contents"));
    }
    @Test void ForeignEntityNativeIdentityAndRootRoleCannotMasqueradeAsPreviousAnalysis(){
        f.jdbc.update("UPDATE contents SET entity_id='fictional-other'");assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE analyzed_entities SET identity_hash=?",new byte[32]);assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE jobs SET task_type='daily_report' WHERE id=?",Fixture.ROOT);assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE jobs SET application_state='DISCARDED' WHERE id=?",Fixture.ROOT);assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE analyzed_entities SET analysis_generation=0");assertThrows(RuntimeException.class,this::read);
    }
    @Test void CorruptActualInputClaimSendAndRetainedArchiveAreRejectedWithoutCurrentBodyFallback(){
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='input' AND part_no=0",new byte[32]);assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE record_kind='ai_attempt'");assertThrows(RuntimeException.class,this::read);
        initialize("agent",true);f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        assertEquals(1,f.count("contents"));assertEquals(1,f.sends);
    }
    @Test void EditedCurrentBodyDoesNotRewriteOldInputAndOuterTransactionsCannotChangeIsolation(){
        var original=read().orElseThrow();f.jdbc.update("UPDATE contents SET body_json=?,body_hash_v1=?,body_revision=body_revision+1,revision=revision+1",f.body.replace("Fictional analysis","Fictional edited analysis"),new byte[32]);
        var previous=read().orElseThrow();assertEquals(original.resultJson(),previous.resultJson());assertEquals(original.inputJson(),previous.inputJson());assertEquals(2,previous.contentRevision());
        var tx=new TransactionTemplate(f.tx);assertThrows(RuntimeException.class,()->tx.execute(s->read()));
        assertThrows(RuntimeException.class,()->service.read("news_tech","fictional-entity","fictional-analysis"));assertEquals(1,f.sends);
    }
    static class Fixture extends ResultJdbcStoreTest {
        String category,body;
        void initializeCatalog(boolean mysql,String category,boolean applied){
            this.category=category;generation=mysql?new GenerationJdbcStoreMySqlTest():new GenerationJdbcStoreTest();generation.fixture();source=generation.source;if(!mysql){var h2=(org.h2.jdbcx.JdbcDataSource)source;h2.setURL(h2.getURL()+";TIME ZONE=UTC");}jdbc=new JdbcTemplate(source);
            for(String table:List.of("contents","article_revisions","analyzed_entities"))jdbc.execute("DROP TABLE IF EXISTS "+table);
            if(!mysql){jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");}
            alter("worker_lock","slot_state VARCHAR(16) DEFAULT 'free',slot_revision BIGINT DEFAULT 0,fencing_token BIGINT DEFAULT 0,job_id VARCHAR(64),holder VARCHAR(64),lease_until "+time(mysql)+",request_deadline "+time(mysql)+",occupied_since "+time(mysql));
            alter("jobs","worker_instance_id VARCHAR(64),dispatch_token BIGINT,request_deadline "+time(mysql)+",send_authorized_at "+time(mysql)+",slot_released_at "+time(mysql)+",release_reason VARCHAR(2048),result_hash BINARY(32),result_json "+(mysql?"JSON":"CLOB")+",application_state VARCHAR(32),report_mode VARCHAR(32)");
            jdbc.execute(mysql?CONTENT_DDL.replace("CLOB","JSON").replace("TIMESTAMP","DATETIME(6)")+" ENGINE=InnoDB":CONTENT_DDL);
            jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key VARCHAR(4096),identity_hash BINARY(32),revision BIGINT,analysis_generation BIGINT,analysis_state VARCHAR(16),legacy_flag INT)");
            var payload=catalogPayload();String identityKey=JdbcFrozenInputs.canonical((payload.has("subject")?payload.get("subject"):payload).get("identity"));
            jdbc.update("INSERT INTO analyzed_entities VALUES('fictional-entity',?,?,?,5,1,'queued',0)",category,identityKey,JdbcEntityDedup.identityHash(category,identityKey));
            tx=new FaultManager(source);writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx,()->25,n->{});
            var stage=Stage.valueOf(category.toUpperCase(Locale.ROOT)+"_ANALYSIS");var context=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(stage));
            context.putArray("reference_targets").addObject().put("kind","catalog_evidence").put("item_id","fictional-item");
            context.putArray("allowed_citation_ids").add(AnalysisPublicMetadataEnricher.citationId(context.get("reference_targets").get(0)));
            var ctx=FrozenApplicationContext.fromJson(context);var evidence=new Evidence("fictional-item",payload.has("subject")?"metric":"catalog",LocalDate.of(2026,1,1),clock.instant().minusSeconds(1),inputBodyHash(payload),JdbcFrozenInputs.canonical(payload),true,false,"accepted",List.of());
            Prepare command=new Prepare("fictional-analysis-key",1,stage,LocalDate.of(2026,1,1),null,List.of(),List.of(evidence),List.of(),true,"a".repeat(64),ctx);
            command=adjustCommand(command,payload);
            var frozen=new GenerationJdbcStore(source,clock,"fictional-owner",ROOT,command);
            preparation(frozen,(s,snapshot,hash)->"{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional catalog analysis fixture\"}],\"response_format\":{\"type\":\"json_object\"},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}",payload,command).prepare(command,action("fictional-prepare"));
            worker=new WorkerAuthorizationService(writes,new WorkerJdbcStore(new JdbcWorkerState(source),new JdbcJobRecords(source),"fictional-epoch",()->false,id->true,clock),clock,()->true);
            var claim=worker.claim(action("fictional-claim"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());permit=claim.permit();identity=permit.identity();assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(permit,request->{sends++;return "fictional response";}).state());
            var reads=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
            // Source reference authority is explicitly a fixture port; this test qualifies the native analysis chain only.
            store=new ResultJdbcStore(source,identity,reads,this::referencesValid,clock,"fictional-owner");
            var handler=new ContentResultHandler(new GeneratedContentService(new JdbcGeneratedContents(source),clock),store::context,new AnalysisResultDownstream(new JdbcFrozenInputs(source),reads,new EntityAnalysisStateService(new JdbcEntityAnalysisState(source))));
            Handler unused=(a,b,c,d)->{throw new AssertionError("unassembled fixture branch");};service=new ResultApplicationService(writes,store,Map.of("analysis",handler,"report",unused,"dedup",unused,"value",unused,"ad_hoc",unused));
            body="{\"title\":\"Fictional analysis\",\"sections\":[],\"citation_ids\":[],\"category\":\""+category+"\",\"entity_id\":\"fictional-entity\"}";
            if(applied)assertEquals(Status.APPLIED,apply().status());
        }
        ObjectNode catalogPayload(){return new CatalogVersionEvidenceMarkerTest().catalog(category);}
        boolean referencesValid(tools.jackson.databind.JsonNode target){return "catalog_evidence".equals(target.path("kind").asText())&&"fictional-item".equals(target.path("item_id").asText());}
        String inputBodyHash(tools.jackson.databind.JsonNode payload){return CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload);}
        Prepare adjustCommand(Prepare command,ObjectNode payload){return command;}
        GenerationFlowService preparation(GenerationJdbcStore frozen,RequestBuilder requests,ObjectNode payload,Prepare command){return new GenerationFlowService(writes,frozen,requests,clock,()->true);}
        @Override String raw(){return ResultApplicationService.providerEnvelope(store.input(),"{\"kind\":\"analysis\",\"body\":"+body+"}");}
        static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new AssertionError(e);}}
    }
}
