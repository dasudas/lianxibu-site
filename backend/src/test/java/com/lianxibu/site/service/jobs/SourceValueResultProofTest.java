package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceValueResultProofTest {
    ValueFlagFactoryTest f;SourceValueResultProofService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {f=new ValueFlagFactoryTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueResultProofTest.this.fixture();}};f.setup();service=service();}
    SourceValueResultProofService service(){return new SourceValueResultProofService(new JdbcSourceDedupResults(f.f.f.source),f.f.plans,f.f.p1.universes,f.f.dependencies);}
    SourceValueFragmentProof read(){return f.f.readonly(()->service.read(f.identity.rootId()));}
    @Test void actualAppliedProofSurvivesLaterCurrentMemberChangesAndRestart() {
        f.apply();var first=read();assertEquals(f.base.base.entity,first.members().getFirst().entityId());int count=f.f.f.count("1=1");f.f.f.jdbc.update("UPDATE source_run_items SET revision=20,entity_id='fictional-later-entity',value_json=NULL WHERE id=?",f.base.base.item);service=service();var restarted=read();assertEquals(first.result(),restarted.result());assertEquals(first.members(),restarted.members());assertEquals(first.handoffHash(),restarted.handoffHash());assertEquals(count,f.f.f.count("1=1"));
    }
    @Test void unfinishedReturnsNullWithoutSavingProofAndPartialCompletionRefuses() {
        int count=f.f.f.count("1=1");assertNull(read());assertEquals(count,f.f.f.count("1=1"));f.f.f.jdbc.update("UPDATE jobs SET application_state='APPLIED' WHERE id=?",f.identity.rootId());assertThrows(RuntimeException.class,this::read);
    }
    @Test void writerAndOutsideTransactionsCannotReadACommittedProof() {assertThrows(RuntimeException.class,()->service.read(f.identity.rootId()));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.read(f.identity.rootId())));}
    @Test void wrongRootTypeBadNativeP1AndEarlierAppliedTimeRefuse() {
        f.apply();f.f.f.jdbc.update("UPDATE jobs SET task_type='news_dedup' WHERE id=?",f.identity.rootId());assertThrows(RuntimeException.class,this::read);f.f.f.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",f.identity.rootId());
        f.f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",java.sql.Timestamp.from(f.base.plan.scope().cutoff().minusSeconds(1)),f.identity.rootId());assertThrows(RuntimeException.class,this::read);f.f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant()),f.identity.rootId());
        f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void locallyRehashedHandoffEntityStillCannotReplaceTheOriginalBinding() {
        f.apply();String id=JdbcFrozenInputs.stableId("member-stage","member-stage:"+f.identity.rootId());var raw=f.f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);var value=(ObjectNode)CanonicalJson.parse(raw);((ObjectNode)value.get("members").get(0)).put("entityId","fictional-swapped");String changed=JdbcFrozenInputs.canonical(value);f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",changed,JdbcGenerationLineage.hash(changed),id);assertThrows(RuntimeException.class,this::read);
    }
    @Test void damagedRetainedResultAndRehashedLogicalAliasNeverProveAnApplication() {
        f.apply();String original=f.base.plan.logicalKey();String alias="fictional-other-value-key";f.f.f.jdbc.update("UPDATE jobs SET logical_key=?,logical_key_hash=? WHERE id=?",alias,JdbcGenerationLineage.hash(alias),f.identity.rootId());assertThrows(RuntimeException.class,this::read);
        f.f.f.jdbc.update("UPDATE jobs SET logical_key=?,logical_key_hash=? WHERE id=?",original,JdbcGenerationLineage.hash(original),f.identity.rootId());f.f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='result'",new byte[32],f.identity.rootId());assertThrows(RuntimeException.class,this::read);
    }
}
