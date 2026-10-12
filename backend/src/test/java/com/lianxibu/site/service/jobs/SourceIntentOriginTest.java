package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

class SourceIntentOriginTest {
    NewsSourcePlanPreparationTest fixture;GenerationJdbcStoreTest f;JdbcSourceIntentScan store;SourceIntentOriginService service;JdbcSourceIntentScan.Candidate candidate;String receipt;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        fixture=new NewsSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceIntentOriginTest.this.database();}};fixture.setup();f=fixture.f;install();candidate=readonly(()->store.page(null,50)).candidates().getFirst();receipt=fixture.collection.dispositionId();
    }
    void install(){store=new JdbcSourceIntentScan(f.source);service=new SourceIntentOriginService(store,new SourceValueIntentReceiptService(new JdbcSourceValueIntents(f.source),new JdbcSourceStageAvailability(f.source)));}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    SourceIntentOriginService.Route read(){return readonly(()->service.read(candidate));}
    void reject(){assertThrows(IllegalStateException.class,this::read);}
    @Test void actualOriginalCollectionProvidesOnlyAnImmutableRouteWithoutCurrentSourceReads(){
        int count=f.count("1=1");var route=read();assertEquals(candidate.id(),route.intentId());assertEquals("news_dedup",route.type());assertEquals("fictional-run",route.runId());assertNull(route.p1Ref());assertEquals("SourceIntentOriginRoute[redacted]",route.toString());
        f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");f.jdbc.update("DELETE FROM source_run_items");install();assertEquals(route,read());assertEquals(count,f.count("1=1"));assertEquals(0,f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
    @Test void nativeRolesReasonsKindsIdentityAndAllHashesCannotBeForged(){
        for(String field:List.of("operator_id","reason","record_kind","id","target_type")){
            String original=f.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",String.class,receipt);String changed=field.equals("id")?original.toUpperCase(Locale.ROOT):"fictional-forged";
            f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",changed,receipt);reject();f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",original,field.equals("id")?changed:receipt);
        }
        for(String field:List.of("action_key_hash","target_key_hash","action_payload_hash")){
            byte[] original=f.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",byte[].class,receipt);f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",new byte[32],receipt);reject();f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",original,receipt);
        }assertNotNull(read());
    }
    void body(String body){f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",body,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(body))),receipt);}
    @Test void recomputingHashesDoesNotPermitExtraFieldsCoercionOrWrongOriginalRecipes(){
        String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,receipt);
        for(int variant=0;variant<9;variant++){
            var body=(ObjectNode)CanonicalJson.parse(original);var facts=(ObjectNode)body.get("facts");
            switch(variant){case 0->body.put("fictional_extra",true);case 1->facts.put("fictional_extra",true);case 2->facts.put("generation","1");case 3->facts.put("sourceId",42);case 4->facts.put("businessDate","2026-01-02");case 5->facts.put("generation",2);case 6->facts.put("activationStage","trial");case 7->facts.put("runKind","trial");case 8->body.put("intent_id",candidate.id().toUpperCase(Locale.ROOT));}
            body(JdbcFrozenInputs.canonical(body));reject();body(original);
        }assertNotNull(read());
    }
    @Test void boundedMissingAndDuplicateOriginsRefuseInsteadOfInventingAPlan(){
        String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,receipt);var body=(ObjectNode)CanonicalJson.parse(original);body.put("fictional_padding","x".repeat(8193));body(JdbcFrozenInputs.canonical(body));reject();body(original);
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",candidate.id());
        fixture.tx(()->{new JdbcJobRecords(f.source).appendDisposition(new JdbcJobRecords.Disposition("fictional-duplicate-origin","fictional-duplicate-action","collection_handoff",JdbcFrozenInputs.canonical(target),"collection-worker","Terminal collection handed to analysis",original),f.clock.instant());return true;});reject();f.jdbc.update("DELETE FROM jobs WHERE id='fictional-duplicate-origin'");assertNotNull(read());f.jdbc.update("DELETE FROM jobs WHERE id=?",receipt);reject();assertEquals(0,f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
    @Test void aRehashedWrongTargetAndFabricatedCandidateCannotSupplyTheOriginalRoute(){
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",candidate.id());String raw=JdbcFrozenInputs.canonical(target);f.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),receipt);reject();target.put("kind","generation_intent");raw=JdbcFrozenInputs.canonical(target);f.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),receipt);
        var original=candidate;candidate=new JdbcSourceIntentScan.Candidate(original.id(),original.key(),new byte[32],original.generation(),original.type(),original.day(),original.state(),original.revision());reject();candidate=new JdbcSourceIntentScan.Candidate(original.id(),original.key(),original.keyHash(),original.generation(),original.type(),original.day().plusDays(1),original.state(),original.revision());reject();candidate=original;assertNotNull(read());
    }
    @Test void exactReadonlyTransactionIsRequiredAndNothingIsWritten(){
        int count=f.count("1=1");assertThrows(RuntimeException.class,()->service.read(candidate));assertThrows(RuntimeException.class,()->fixture.tx(()->service.read(candidate)));assertEquals(count,f.count("1=1"));assertNotNull(read());
    }
    SourceValueIntentHandoffTest values() throws Exception {var value=new SourceValueIntentHandoffTest(){@Override GenerationJdbcStoreTest database(){return SourceIntentOriginTest.this.database();}};value.setup();f=value.f.f;install();return value;}
    @Test void actualP2OriginRequiresTheOriginalP1ArchiveAndObservation() throws Exception {
        var value=values();var handoff=value.service.handoff(value.ref);candidate=readonly(()->store.page(null,50)).candidates().getFirst();int count=f.count("1=1");var route=read();assertEquals(handoff.p2Owner(),route.intentId());assertEquals("news_value",route.type());assertEquals(handoff.p1Ref(),route.p1Ref());assertEquals(handoff.runId(),route.runId());install();assertEquals(route,read());assertEquals(count,f.count("1=1"));
        f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(value.ref.ownerId()));reject();assertEquals(count,f.count("1=1"));
    }
    @Test void metadataOnlyPrefixSuppliesARouteAndNeverClaimsCompletedBusinessHandoff() throws Exception {
        var value=values();var proof=value.f.readonly(()->value.proofs.read(value.ref));var plan=value.f.readonly(()->value.f.p1.plans.readImmutable(value.ref));var universe=value.f.readonly(()->value.f.p1.universes.readImmutable(plan.reference()));Instant observed=FrozenSourceDedupDependency.read(value.f.profile,value.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)).observedAt();var reserved=SourceValueIntentReceiptService.Handoff.create(proof,universe,observed,1,value.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        value.f.p1.tx(()->value.metadata.reserve(reserved));assertNull(value.read());candidate=readonly(()->store.page(null,50)).candidates().stream().filter(c->c.id().equals(reserved.p2Owner())).findFirst().orElseThrow();int count=f.count("1=1");assertEquals(reserved.p1Ref(),read().p1Ref());assertNull(value.read());assertEquals("queued",value.state());assertEquals(count,f.count("1=1"));
    }

    @Test void originalTerminalValueRemainsFindableForItsSeparatePostSealObservation() throws Exception {
        var value=values();var handoff=value.service.handoff(value.ref);
        for(String state:List.of("succeeded","no_input")){
            f.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,handoff.p2Owner());var page=readonly(()->store.page(null,50));assertEquals(List.of(handoff.p2Owner()),page.candidates().stream().map(JdbcSourceIntentScan.Candidate::id).toList());candidate=page.candidates().getFirst();assertEquals(state,candidate.state());assertEquals(handoff.p1Ref(),read().p1Ref());
        }
        for(String state:List.of("failed","cancelled")){f.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,handoff.p2Owner());assertTrue(readonly(()->store.page(null,50)).candidates().isEmpty());}
        assertEquals(0,f.count("target_type='source_value_final_availability'"));
    }
}
