package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.sources.JdbcCollectionHandoff;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import org.junit.jupiter.api.*;

class CollectionHandoffProofServiceTest {
 CollectionHandoffIntegrationTest fixture;CollectionHandoffProofService service;
 CollectionHandoffIntegrationTest fixture(){return new CollectionHandoffIntegrationTest();}
 @BeforeEach void setup(){fixture=fixture();fixture.setup();fixture.handoff();service=new CollectionHandoffProofService(fixture.f.source,new CollectionHandoffService(new JdbcCollectionHandoff(fixture.f.source),facts->{throw new AssertionError();}));}
 CollectionHandoffProofService.Proof read(){return fixture.f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-read","fictional-run",null),k->null,a->service.read("fictional-run"));}
 @Test void exactOriginalCollectionSurvivesIntentProgressWithoutInventingAiRoot(){var proof=read();assertEquals("fictional-run",proof.facts().runId());assertEquals("production",proof.facts().activationStage());assertEquals(64,proof.payloadHash().length());assertEquals(0,fixture.f.count("record_kind='logical_task'"));fixture.f.jdbc.update("UPDATE jobs SET state='queued',revision=3 WHERE record_kind='generation_intent'");assertEquals(proof,read());}
 @Test void missingOrCorruptPermanentReceiptCannotUseCurrentRunAlone(){fixture.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='collection_handoff'",new byte[32]);assertThrows(RuntimeException.class,this::read);fixture.f.jdbc.update("DELETE FROM jobs WHERE target_type='collection_handoff'");assertThrows(RuntimeException.class,this::read);}
 @Test void changedCurrentConfigurationAndMissingRunLinkReject(){fixture.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{\"fictional_changed\":true}'");assertThrows(RuntimeException.class,this::read);fixture.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}',job_refs_json='[]'");assertThrows(RuntimeException.class,this::read);}
 @Test void rehashedReceiptAndChangedIntentTypeCannotChangeOriginalIdentity(){var jdbc=fixture.f.jdbc;var original=jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='collection_handoff'",String.class);var value=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(original);((tools.jackson.databind.node.ObjectNode)value.get("facts")).put("generation",2);String encoded=JdbcFrozenInputs.canonical(value);jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='collection_handoff'",encoded,com.lianxibu.site.repository.jobs.JdbcGenerationLineage.hash(encoded));assertThrows(RuntimeException.class,this::read);jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='collection_handoff'",original,com.lianxibu.site.repository.jobs.JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(original))));jdbc.update("UPDATE jobs SET task_type='agent_dedup' WHERE record_kind='generation_intent'");assertThrows(RuntimeException.class,this::read);}
}
