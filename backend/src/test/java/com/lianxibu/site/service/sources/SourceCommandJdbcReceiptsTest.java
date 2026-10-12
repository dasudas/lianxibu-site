package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.*;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceCommandJdbcReceiptsTest {
    private final Actor owner=new Actor("fictional-owner","owner");
    private final Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
    private JdbcDataSource source;private JdbcTemplate jdbc;private DataSourceTransactionManager tx;
    @BeforeEach void setup(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:source-receipt-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);tx=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),state VARCHAR(16),config_json CLOB,probe_json CLOB,revision BIGINT,updated_at TIMESTAMP,draft_config_json CLOB,effective_config_json CLOB,config_revision BIGINT,activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
    }
    private SourceCommandJdbcReceipts receipts(Actor actor,String key,String command){return new SourceCommandJdbcReceipts(new JdbcSourceCommandReceipts(source),new OwnerSourceReadService(new JdbcOwnerSourceReads(source,tx)),actor,key,CanonicalJson.parse(command),clock);}
    private SourceAdmissionService service(SourceCommandJdbcReceipts receipts){return new SourceAdmissionService(new JdbcSourceAdmission(source),new SourceIdentityMappingService(new JdbcSourceReads(source),tx,List.of()),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),new SourceAdmissionPolicy(clock),new SourceAdmissionService.EvidenceStore(){public SourceAdmissionPolicy.ProbeEvidence probe(String id,long version){return null;}public SourceAdmissionPolicy.PromotionEvidence promotion(String id,long version){return null;}},receipts,clock);}
    private static SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,"fictional-operation",null);}
    private SourcesService.SourceConfig config(){return new SourcesService.SourceConfig("fictional-adapter",List.of("https://example.invalid/feed"),"native-id","daily","fictional-parser",20,200000,List.of("example.invalid"),clock.instant().plusSeconds(86400),null);}
    @Test void originalSnapshotSurvivesLaterEditRestartAndKeysAreActorScoped(){
        var first=receipts(owner,"fixture-key","{\"action\":\"create\",\"source\":\"fictional-source\"}");
        var command=new SourcesService.SourceCreate("fictional-source","Fictional original","news_tech",config());
        var result=service(first).create(owner,command,action("fixture-key"));
        assertEquals("Fictional original",first.originalSource().get("name"));
        var edit=receipts(owner,"fixture-edit","{\"action\":\"edit\",\"source\":\"fictional-source\",\"name\":\"Fictional edited\"}");
        service(edit).edit(owner,"fictional-source",new SourcesService.SourceWrite(1,"Fictional edited","news_tech",config()),action("fixture-edit"));
        var restarted=receipts(owner,"fixture-key","{\"source\":\"fictional-source\",\"action\":\"create\"}");
        assertEquals(result,service(restarted).create(owner,command,action("fixture-key")));
        assertEquals("Fictional original",restarted.originalSource().get("name"));
        assertEquals(first.operationId(),restarted.operationId());
        assertEquals("Fictional edited",jdbc.queryForObject("SELECT name FROM sources",String.class));
        assertNull(receipts(new Actor("fictional-other","owner"),"fixture-key","{\"action\":\"create\"}").committed("fixture-key"));
        assertThrows(IllegalArgumentException.class,()->receipts(owner,"fixture-key","{\"action\":\"disable\"}").committed("fixture-key"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void receiptFailureRollsBackSourceAndCurrentRevision(){
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT reject_receipt CHECK(status_code=500)");
        var receipts=receipts(owner,"fixture-key","{\"action\":\"create\",\"source\":\"fictional-source\"}");
        assertThrows(RuntimeException.class,()->service(receipts).create(owner,new SourcesService.SourceCreate("fictional-source","Fictional","news_tech",config()),action("fixture-key")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sources",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void registryHashesCompleteConfigurationAndDisableReplayCannotReapply(){
        var registry=new SourceRegistryService(new JdbcSourceDirectory(source),new OwnerSourceReadService(new JdbcOwnerSourceReads(source,tx)),(actor,key,command)->{
            var receipts=receipts(actor,key,command.toString());return new SourceRegistryService.BoundCommand(service(receipts),receipts);
        },tx);
        var command=new SourcesService.SourceCreate("fictional-source","Fictional original","news_tech",config());
        var created=registry.createSource(owner,command,"fixture-create",null,null);
        assertEquals(1,created.revision());assertEquals("disabled",created.activationStage());
        var original=config();var changed=new SourcesService.SourceConfig(original.adapter(),original.entryUrls(),original.identityRule(),original.windowRule(),original.parserVersion(),21,original.maxResponseBytes(),original.allowedHosts(),original.validUntil(),null);
        assertThrows(IllegalArgumentException.class,()->registry.createSource(owner,new SourcesService.SourceCreate(command.id(),command.name(),command.category(),changed),"fixture-create",null,null));
        registry.writeSource(owner,command.id(),new SourcesService.SourceWrite(1,"Fictional edited","news_tech",changed),"fixture-edit",null,null);
        assertEquals("Fictional original",registry.createSource(owner,command,"fixture-create",null,null).name());
        assertEquals("Fictional edited",registry.listSources(owner,1,20).items().getFirst().name());
        jdbc.update("UPDATE sources SET activation_stage='production'");
        var disable=new SourcesService.SourceAction(2,"disable","Fictional reason");
        var result=registry.sourceAction(owner,command.id(),disable,"fixture-disable",null,null);
        assertEquals(3,result.resultRevision());
        jdbc.update("UPDATE sources SET activation_stage='production'");
        assertEquals(result,registry.sourceAction(owner,command.id(),disable,"fixture-disable",null,null));
        assertEquals("production",jdbc.queryForObject("SELECT activation_stage FROM sources",String.class));
        assertThrows(com.lianxibu.site.service.identity.IdentityService.Rejected.class,()->registry.listSources(new Actor("fictional-reader","user"),1,20));
    }
}
