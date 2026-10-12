package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.sources.JdbcCollectionHandoff;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import org.junit.jupiter.api.*;

class CollectionTerminalFactsTest {
 CollectionHandoffIntegrationTest fixture;
 CollectionHandoffIntegrationTest fixture(){return new CollectionHandoffIntegrationTest();}
 CollectionHandoffService service;
 @BeforeEach void setup(){fixture=fixture();fixture.setup();fixture.handoff();service=new CollectionHandoffService(new JdbcCollectionHandoff(fixture.f.source),facts->{throw new AssertionError("read must not create an intention");});}
 CollectionHandoffService.Terminal read(String id){return fixture.f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-read",id,null),k->null,a->service.terminalFacts(id));}
 @Test void readKeepsOriginalFactsAndDoesNotWrite(){var before=fixture.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class);var proof=read("fictional-run");assertEquals(2,proof.facts().accepted());assertEquals("production",proof.facts().activationStage());assertEquals(2,proof.jobRefs().size());assertThrows(UnsupportedOperationException.class,()->proof.jobRefs().clear());assertEquals(before,fixture.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));assertThrows(RuntimeException.class,()->service.terminalFacts("fictional-run"));}
 @Test void unknownAliasProbeAndPendingCannotBecomeTerminalProof(){assertThrows(RuntimeException.class,()->read("fictional-missing"));assertThrows(RuntimeException.class,()->read("FICTIONAL-run"));fixture.f.jdbc.update("UPDATE source_runs SET pending_count=1");assertThrows(RuntimeException.class,()->read("fictional-run"));fixture.f.jdbc.update("UPDATE source_runs SET pending_count=0,run_kind='probe'");assertThrows(RuntimeException.class,()->read("fictional-run"));}
 @Test void sourceAliasAndMalformedRefsRefuseWithoutRepair(){fixture.f.jdbc.update("UPDATE source_runs SET source_id='FICTIONAL-source'");assertThrows(RuntimeException.class,()->read("fictional-run"));fixture.f.jdbc.update("UPDATE source_runs SET source_id='fictional-source',job_refs_json='[\"fictional-a\",\"fictional-a\"]'");assertThrows(RuntimeException.class,()->read("fictional-run"));}
 @Test void trialNeverBecomesProductionOnRead(){fixture.f.jdbc.update("UPDATE source_runs SET activation_stage='trial',run_kind='trial'");assertEquals("trial",read("fictional-run").facts().activationStage());}
 @Test void oversizedReferenceSummaryIsRejectedWithoutTruncation(){fixture.f.jdbc.execute("ALTER TABLE source_runs MODIFY job_refs_json LONGTEXT");var refs=tools.jackson.databind.json.JsonMapper.shared().createArrayNode();for(int n=0;n<1100;n++)refs.add("fictional-"+"a".repeat(48)+String.format("%04d",n));fixture.f.jdbc.update("UPDATE source_runs SET job_refs_json=?",refs.toString());assertThrows(RuntimeException.class,()->read("fictional-run"));assertEquals(refs,com.lianxibu.site.domain.protocol.CanonicalJson.parse(fixture.f.jdbc.queryForObject("SELECT job_refs_json FROM source_runs",String.class)));}
}
