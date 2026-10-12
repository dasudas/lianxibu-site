package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class GenerationPreparationReceiptTest {
    GenerationJdbcStoreTest f;GenerationFlowService.Frozen original;String id,body,target;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=database();f.fixture();original=f.flow(f.store).prepare(f.command(false),f.action());id=JdbcFrozenInputs.stableId("prepare-action",GenerationJdbcStoreTest.ACTION);body=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);target=f.jdbc.queryForObject("SELECT target_key_json FROM jobs WHERE id=?",String.class,id);}
    void rejected(){assertThrows(RuntimeException.class,()->f.store.committed(GenerationJdbcStoreTest.ACTION));}
    void payload(ObjectNode n){String raw=JdbcFrozenInputs.canonical(n);f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);}
    ObjectNode payload(){return (ObjectNode)CanonicalJson.parse(body);}
    void target(ObjectNode n){String raw=JdbcFrozenInputs.canonical(n);f.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);}
    @Test void originalReceiptReplaysWithoutFollowingCurrentRootState(){int count=f.count("1=1");f.jdbc.update("UPDATE jobs SET state='running',revision=4 WHERE id=?",original.ownerId());assertEquals(original,f.store.committed(GenerationJdbcStoreTest.ACTION));assertEquals(count,f.count("1=1"));}
    @Test void exactNativeIdKindTypeActorReasonAndActionHashAreRequired(){
        f.jdbc.update("UPDATE jobs SET id='fictional-other-receipt' WHERE id=?",id);rejected();f.jdbc.update("UPDATE jobs SET id=? WHERE id='fictional-other-receipt'",id);
        for(var field:Map.of("record_kind","generation_intent","target_type","fictional_wrong_type","operator_id","fictional-other-actor","reason","Fictional other reason").entrySet()){String old=f.jdbc.queryForObject("SELECT "+field.getKey()+" FROM jobs WHERE id=?",String.class,id);f.jdbc.update("UPDATE jobs SET "+field.getKey()+"=? WHERE id=?",field.getValue(),id);rejected();f.jdbc.update("UPDATE jobs SET "+field.getKey()+"=? WHERE id=?",old,id);}
        f.jdbc.update("UPDATE jobs SET action_key_hash=? WHERE id=?",new byte[32],id);rejected();
    }
    @Test void rehashedWrongTargetKindExtraFieldAndNontextIdAreRejected(){
        var n=(ObjectNode)CanonicalJson.parse(target);n.put("kind","generation_intent");target(n);rejected();n=(ObjectNode)CanonicalJson.parse(target);n.put("fictional_extra",true);target(n);rejected();n=(ObjectNode)CanonicalJson.parse(target);n.put("id",123);target(n);rejected();
    }
    @Test void nativeTargetHashAndPayloadHashCannotBeIgnored(){
        f.jdbc.update("UPDATE jobs SET target_key_hash=? WHERE id=?",new byte[32],id);rejected();target((ObjectNode)CanonicalJson.parse(target));f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],id);rejected();
    }
    @Test void rehashedPayloadIsClosedAndNeverCoercesTypes(){
        var n=payload();n.put("fictional_extra",true);payload(n);rejected();n=payload();n.remove("missing");payload(n);rejected();n=payload();n.put("state",1);payload(n);rejected();n=payload();n.put("schema_version",1);payload(n);rejected();n=payload();n.put("input_hash",true);payload(n);rejected();
    }
    @Test void rehashedTimesCoverageStateAndMissingValuesRemainStrict(){
        for(String cutoff:List.of("2026-01-01T21:00:00.123456789Z","2026-01-01T21:00:00+00:00","invalid")){var n=payload();n.put("cutoff",cutoff);payload(n);rejected();}
        var n=payload();n.put("state","fictional_unknown");payload(n);rejected();n=payload();n.put("coverage_state","fictional_unknown");payload(n);rejected();n=payload();n.putArray("missing").add(1);payload(n);rejected();n=payload();n.putArray("missing").add("fictional-gap").add("fictional-gap");n.put("coverage_state","partial");payload(n);rejected();
    }
    @Test void oversizedRehashedBodiesAndTargetsCannotBecomePrepareEvidence(){
        var n=(ObjectNode)CanonicalJson.parse(target);n.put("fictional_padding","x".repeat(4097));target(n);rejected();target((ObjectNode)CanonicalJson.parse(target));n=payload();n.put("fictional_padding","x".repeat(65537));payload(n);rejected();
    }
}
