package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Native source qualification at freeze plus actual original worker/result chain; external sender remains fictional. */
class CatalogPreviousAnalysisEvidenceTest {
    Fixture f;boolean mysql(){return false;}
    @BeforeEach void setup(){initialize("agent",false);}
    void initialize(String category,boolean fault){f=new Fixture();f.fault=fault;f.initializeCatalog(mysql(),category,true);}
    CatalogPreviousAnalysisService.Previous previous(){return new CatalogPreviousAnalysisService(f.source,f.tx).read(f.category,"fictional-entity","fictional-analysis").orElseThrow();}
    @Test void CompleteFourCategoryOriginalEvidenceSurvivesRemovalOfCurrentSourceTables(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var original=previous();var evidence=CatalogPreviousAnalysisEvidence.read(original).orElseThrow();
            assertEquals(1,evidence.members().size());assertEquals("fictional-item",evidence.members().getFirst().id());
            assertEquals(original.inputHash(),evidence.previous().inputHash());assertEquals(original.identityJson(),new String(CanonicalJson.bytes(CanonicalJson.parse(evidence.members().getFirst().payloadJson()).get("identity")),java.nio.charset.StandardCharsets.UTF_8));
            f.jdbc.execute("DROP TABLE source_run_items");f.jdbc.execute("DROP TABLE source_runs");
            assertEquals(evidence.members(),CatalogPreviousAnalysisEvidence.read(previous()).orElseThrow().members());
            assertThrows(UnsupportedOperationException.class,()->evidence.members().clear());assertEquals(1,f.sends);
        }
    }
    @Test void OldInputWithoutPublicMetadataIsUnknownDespiteAnActualSuccessfulAnalysis(){
        var basic=new CatalogPreviousAnalysisTest.Fixture();basic.initializeCatalog(mysql(),"agent",true);
        var previous=new CatalogPreviousAnalysisService(basic.source,basic.tx).read("agent","fictional-entity","fictional-analysis").orElseThrow();
        assertTrue(CatalogPreviousAnalysisEvidence.read(previous).isEmpty());assertEquals(1,basic.sends);
    }
    @Test void RehashedProducerFaultCannotTurnChangedOriginalMemberHashIntoEvidence(){
        initialize("agent",true);assertThrows(RuntimeException.class,()->CatalogPreviousAnalysisEvidence.read(previous()));assertEquals(1,f.sends);
    }
    @Test void NativeWrongSourceAssignmentAndConfigurationRejectBeforeAnyWorkerBudget(){
        var bad=new Fixture();bad.breakSource=true;assertThrows(RuntimeException.class,()->bad.initializeCatalog(mysql(),"agent",true));
        assertEquals(0,bad.sends);assertEquals(0,bad.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
    }
    @Test void MaximumHundredOriginalMembersAreAllRetainedAndNeverTruncated(){
        f=new Fixture();f.members=100;f.initializeCatalog(mysql(),"agent",true);var evidence=CatalogPreviousAnalysisEvidence.read(previous()).orElseThrow();
        assertEquals(100,evidence.members().size());assertEquals(100,evidence.members().stream().map(CatalogPreviousAnalysisEvidence.Member::id).distinct().count());assertTrue(evidence.members().stream().anyMatch(m->m.id().equals("fictional-item-99")));assertEquals(1,f.sends);
    }
    @Test void NativeRootCutoffCannotDisagreeWithOriginalFrozenMembersCutoff(){
        f.jdbc.update("UPDATE jobs SET input_cutoff_at=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant().plusSeconds(1)),CatalogPreviousAnalysisTest.Fixture.ROOT);
        assertThrows(RuntimeException.class,()->CatalogPreviousAnalysisEvidence.read(previous()));assertEquals(1,f.sends);
    }
    static class Fixture extends CatalogPreviousAnalysisTest.Fixture {
        boolean fault,breakSource;int members=1;
        @Override GenerationFlowService preparation(GenerationJdbcStore frozen,RequestBuilder request,ObjectNode payload,Prepare command){
            String text=mysqlText(),time=mysqlTime();
            for(String table:List.of("source_run_items","source_runs"))jdbc.execute("DROP TABLE IF EXISTS "+table);
            jdbc.execute("ALTER TABLE analyzed_entities ADD first_seen_business_date DATE");jdbc.execute("ALTER TABLE analyzed_entities ADD occurrence_count BIGINT");
            jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),activation_stage VARCHAR(16),delivery_state VARCHAR(24),finished_at "+time+",config_version BIGINT,config_snapshot_json "+text+",business_date DATE)");
            jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),revision BIGINT,category VARCHAR(16),created_at "+time+",terminal_state VARCHAR(16),state VARCHAR(32),projection_decision VARCHAR(16),effective_valid INT,evidence_json "+text+",evidence_hash BINARY(32),entity_id VARCHAR(64),source_item_id VARCHAR(256))");
            jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','production','complete',?,1,?,?)",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),"{\"parser_version\":\"fictional-parser-v1\"}",java.sql.Date.valueOf(LocalDate.of(2026,1,1)));
            for(var item:command.evidence())jdbc.update("INSERT INTO source_run_items VALUES(?,'fictional-run',?,2,?,?,'accepted','ready','initialized',1,?,?, 'fictional-entity',?)",item.id(),breakSource?"fictional-foreign-source":"fictional-source",category,java.sql.Timestamp.from(clock.instant().minusSeconds(2)),item.bodyJson(),HexFormat.of().parseHex(sha(CanonicalJson.bytes(CanonicalJson.parse(item.bodyJson())))),item.id());
            var sourceFacts=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));
            var actual=new AnalysisPublicMetadataEnricher(new EntityInputEvidenceService(new JdbcEntityDedup(source),sourceFacts),new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(source),sourceFacts));
            InputEnricher enricher=actual;
            if(fault)enricher=(root,frozenCommand,cutoff,snapshot)->{((ObjectNode)snapshot.get("members").get(0)).put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,snapshot.get("members").get(0).get("body")));return actual.fields(root,frozenCommand,cutoff,snapshot);};
            return new GenerationFlowService(writes,frozen,request,clock,()->true,null,enricher);
        }
        @Override boolean referencesValid(tools.jackson.databind.JsonNode target){
            if(!"catalog_evidence".equals(target.path("kind").asText()))return false;
            return jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE id=? AND entity_id='fictional-entity' AND category=? AND state='ready' AND effective_valid=1",Integer.class,WireValues.id(target.get("item_id"),false),category)==1;
        }
        @Override Prepare adjustCommand(Prepare command,ObjectNode payload){
            if(members==1)return command;
            var context=(ObjectNode)command.applicationContext().toJson();var ids=context.putArray("item_ids");var candidates=context.putArray("candidates_by_item");var targets=context.putArray("reference_targets");var citations=context.putArray("allowed_citation_ids");var evidence=new ArrayList<Evidence>();
            for(int n=0;n<members;n++){String id=n==0?"fictional-item":"fictional-item-"+n;ids.add(id);candidates.addObject().put("item_id",id).putArray("entity_ids").add("fictional-entity");var target=targets.addObject().put("kind","catalog_evidence").put("item_id",id);citations.add(AnalysisPublicMetadataEnricher.citationId(target));evidence.add(new Evidence(id,payload.has("subject")?"metric":"catalog",command.businessDate(),clock.instant().minusSeconds(1),inputBodyHash(payload),JdbcFrozenInputs.canonical(payload),true,false,"accepted",List.of()));}
            return new Prepare(command.logicalKey(),command.generation(),command.stage(),command.businessDate(),command.sourceId(),command.dependencies(),evidence,command.expectedDependencies(),command.explicitSupplement(),command.settingsHash(),FrozenApplicationContext.fromJson(context));
        }
        @Override String inputBodyHash(tools.jackson.databind.JsonNode payload){return fault?"0".repeat(64):super.inputBodyHash(payload);}
        String mysqlText(){return source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";}
        String mysqlTime(){return source instanceof org.h2.jdbcx.JdbcDataSource?"TIMESTAMP":"DATETIME(6)";}
    }
}
