package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Native original claim/send/unknown protocol, shared writer, durable shards and receipt. */
class OwnerExternalResolutionLedgerTest extends OwnerExternalResolutionMaterialTest {
    JdbcOwnerExternalResolution ledger;
    @Override @BeforeEach void setup(){super.setup();ledger=new JdbcOwnerExternalResolution(f.source);}
    JdbcOwnerExternalResolution.Command material(JobAction action){return new JdbcOwnerExternalResolution.Command(verify(read(),action).evidence().canonical(),f.identity.inputHash());}
    JobAction decode(String raw){
        var v=CanonicalJson.parse(raw);var result=v.get("result");var usage=v.get("usage");var refs=new ArrayList<String>();v.get("evidence_refs").forEach(x->refs.add(x.textValue()));
        return new JobAction(v.get("expected_revision").longValue(),"resolve_external",v.get("reason").textValue(),v.get("attempt_id").textValue(),null,null,v.get("external_state").textValue(),refs,result.isNull()?null:new ValidatedResult(result.get("schema_version").textValue(),result.get("input_hash").textValue(),result.get("payload"),result.get("result_hash").textValue()),usage.isNull()?null:new Usage(number(usage.get("input_tokens")),number(usage.get("output_tokens")),usage.get("cost").isNull()?null:usage.get("cost").textValue()));
    }
    static String hashResult(JsonNode value){try{var d=java.security.MessageDigest.getInstance("SHA-256");d.update("ai-result-v1\n".getBytes(StandardCharsets.UTF_8));d.update(CanonicalJson.bytes(value));return HexFormat.of().formatHex(d.digest());}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    static Long number(JsonNode value){return value.isNull()?null:value.longValue();}
    void semantics(JdbcOwnerExternalAttemptReads.Frame frame,String raw){assertEquals(raw,verify(frame,decode(raw)).evidence().canonical());}
    JdbcOwnerExternalResolution.Resolved execute(String key,JdbcOwnerExternalResolution.Command command){var frame=read();return f.writes.execute(f.action(key),k->ledger.committed(k,command,frame,this::semantics),a->ledger.resolve(a.actionKey(),command,f.clock.instant(),this::semantics));}
    JobAction withUsage(JobAction c,Usage u){return new JobAction(c.expectedRevision(),c.action(),c.reason(),c.attemptId(),null,null,c.externalState(),c.evidenceRefs(),c.result(),u);}
    String state(String id){return f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,id);}
    long records(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);}
    String original(){return f.jdbc.queryForObject("SELECT request_json FROM jobs WHERE id=?",String.class,f.identity.attemptId());}
    void rejected(String code,org.junit.jupiter.api.function.Executable work){assertEquals(code,assertThrows(JdbcOwnerExternalResolution.Rejected.class,work).getMessage());}

    @Test void LatestSuccessRetainsOriginalResultAndDoesNotApplyContentOrChangeCurrentSlot(){
        var c=material(command("succeeded"));var slot=new JdbcWorkerState(f.source).slot();var input=read().inputJson();var request=original();var result=execute("fictional-resolve-success",c);
        assertTrue(result.rootResultRetained());assertEquals("succeeded",state(f.identity.attemptId()));assertEquals("result_conflict",state(ResultJdbcStoreTest.ROOT));assertEquals("EXTERNAL_RESULT_CONFIRMED",f.jdbc.queryForObject("SELECT application_state FROM jobs WHERE id=?",String.class,ResultJdbcStoreTest.ROOT));assertEquals(canonicalResult(c),f.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE purpose='result'",String.class).transform(v->JdbcFrozenInputs.canonical(CanonicalJson.parse(v))));
        assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(input,read().inputJson());assertEquals(request,original());assertEquals(1,read().root().callCount());assertEquals(0,f.count("contents"));assertEquals(1,f.sends);
        long rows=records();f.jdbc.update("UPDATE jobs SET state='closed_unresolved',revision=revision+4 WHERE id=?",ResultJdbcStoreTest.ROOT);f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',holder='fictional-new-holder',job_id='fictional-new-job',slot_revision=71,fencing_token=71");slot=new JdbcWorkerState(f.source).slot();assertEquals(result,execute("fictional-resolve-success",c));assertEquals(rows,records());assertEquals(slot,new JdbcWorkerState(f.source).slot());
    }
    String canonicalResult(JdbcOwnerExternalResolution.Command c){return JdbcFrozenInputs.canonical(CanonicalJson.parse(c.canonical()).get("result"));}
    @Test void FailedLatestCallClosesUnknownRootAndUnknownUsageRemainsNull(){
        var c=material(command("failed"));assertFalse(execute("fictional-resolve-failed",c).rootResultRetained());assertEquals("failed",state(ResultJdbcStoreTest.ROOT));assertEquals("failed",state(f.identity.attemptId()));assertNull(f.jdbc.queryForObject("SELECT usage_json FROM jobs WHERE id=?",String.class,f.identity.attemptId()));assertNull(f.jdbc.queryForObject("SELECT cost_decimal FROM jobs WHERE id=?",BigDecimal.class,f.identity.attemptId()));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE purpose='result'",Integer.class));
    }
    @Test void UnknownAppendsEvidenceAndCanLaterBeResolvedUnderNewRootCas(){
        var c=material(command("unknown"));var first=execute("fictional-resolve-unknown",c);assertFalse(first.rootResultRetained());assertEquals("unknown_external",state(ResultJdbcStoreTest.ROOT));assertEquals("unknown_external",state(f.identity.attemptId()));var second=execute("fictional-resolve-later",material(command("succeeded")));assertTrue(second.rootResultRetained());assertEquals(first.revision()+1,second.revision());assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='job_external_resolution'",Integer.class));
    }
    @Test void OlderAttemptAndClosedRootOnlyReceiveAuditAndNeverReplaceRootResult(){
        f.jdbc.update("UPDATE jobs SET call_count=2,state='running',revision=revision+2 WHERE id=?",ResultJdbcStoreTest.ROOT);var slot=new JdbcWorkerState(f.source).slot();assertFalse(execute("fictional-resolve-older",material(command("succeeded"))).rootResultRetained());assertEquals("running",state(ResultJdbcStoreTest.ROOT));assertNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());assertEquals(2,read().root().callCount());assertEquals(slot,new JdbcWorkerState(f.source).slot());
        setup();f.jdbc.update("UPDATE jobs SET state='closed_unresolved',revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);assertFalse(execute("fictional-resolve-closed",material(command("succeeded"))).rootResultRetained());assertEquals("closed_unresolved",state(ResultJdbcStoreTest.ROOT));assertNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());assertEquals("succeeded",state(f.identity.attemptId()));
    }
    @Test void KnownUsageMergesAliasesWithoutErasingKnownValuesOrOtherOriginalFields(){
        f.jdbc.update("UPDATE jobs SET usage_json=?,cost_decimal=? WHERE id=?","{\"prompt_tokens\":17,\"completion_tokens\":null,\"state\":\"unknown\",\"fictional_marker\":\"original\"}",new BigDecimal("0.12345678"),f.identity.attemptId());execute("fictional-resolve-usage",material(withUsage(command("unknown"),new Usage(null,21L,null))));var u=CanonicalJson.parse(f.jdbc.queryForObject("SELECT usage_json FROM jobs WHERE id=?",String.class,f.identity.attemptId()));assertEquals(17,u.get("prompt_tokens").longValue());assertEquals(21,u.get("completion_tokens").longValue());assertEquals("original",u.get("fictional_marker").textValue());assertEquals(0,new BigDecimal("0.12345678").compareTo(f.jdbc.queryForObject("SELECT cost_decimal FROM jobs WHERE id=?",BigDecimal.class,f.identity.attemptId())));
        execute("fictional-resolve-usage-null",material(command("unknown")));u=CanonicalJson.parse(f.jdbc.queryForObject("SELECT usage_json FROM jobs WHERE id=?",String.class,f.identity.attemptId()));assertEquals(17,u.get("prompt_tokens").longValue());assertEquals(21,u.get("completion_tokens").longValue());
    }
    @Test void ConflictingKnownTokensAndCostAreBusinessConflictsBeforeAnyWrite(){
        f.jdbc.update("UPDATE jobs SET usage_json=?,cost_decimal=? WHERE id=?","{\"input_tokens\":17,\"output_tokens\":21}",new BigDecimal("0.12"),f.identity.attemptId());long count=records(),revision=read().root().revision();rejected("identity_conflict",()->execute("fictional-token-conflict",material(withUsage(command("succeeded"),new Usage(18L,null,null)))));rejected("identity_conflict",()->execute("fictional-cost-conflict",material(withUsage(command("succeeded"),new Usage(null,null,"0.13")))));assertEquals(count,records());assertEquals(revision,read().root().revision());assertEquals("unknown_external",state(f.identity.attemptId()));
    }
    @Test void NegativeAndOverflowPhysicalCostsAreInvalidEvidenceWithNoSqlPartialWrite(){
        long rows=records();for(String cost:List.of("-0.1","1000000000000","999999999999999999999999999999"))rejected("invalid_evidence",()->execute("fictional-invalid-cost",material(withUsage(command("succeeded"),new Usage(null,null,cost)))));assertEquals(rows,records());assertNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());execute("fictional-max-cost",material(withUsage(command("failed"),new Usage(0L,0L,"999999999999.99999999"))));assertEquals(0,new BigDecimal("999999999999.99999999").compareTo(f.jdbc.queryForObject("SELECT cost_decimal FROM jobs WHERE id=?",BigDecimal.class,f.identity.attemptId())));
    }
    @Test void RootCasAndTerminalAttemptRefuseNewActionsWithoutMutatingLedger(){
        var stale=material(command("succeeded"));f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);long count=records();rejected("revision_conflict",()->execute("fictional-stale-root",stale));assertEquals(count,records());execute("fictional-first-terminal",material(command("failed")));count=records();rejected("run_frozen",()->execute("fictional-second-terminal",material(command("unknown"))));assertEquals(count,records());
    }
    @Test void EntireLedgerAndRootAttemptUpdatesRollBackWithOuterTransaction(){
        var c=material(command("succeeded"));long count=records(),revision=read().root().revision();assertThrows(IllegalStateException.class,()->f.writes.execute(f.action("fictional-roll-back"),k->null,a->{ledger.resolve(a.actionKey(),c,f.clock.instant(),this::semantics);throw new IllegalStateException("Fictional later failure");}));assertEquals(count,records());assertEquals(revision,read().root().revision());assertEquals("unknown_external",state(f.identity.attemptId()));assertNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());
    }
    @Test void CommitAckLossRecoversExactOriginalReceiptAndDoesNotWriteTwice(){
        var c=material(command("succeeded"));var frame=read();f.tx.ackLost=true;var result=f.writes.execute(f.action("fictional-ack-lost"),k->ledger.committed(k,c,frame,this::semantics),x->ledger.resolve(x.actionKey(),c,f.clock.instant(),this::semantics));long count=records();assertEquals(result,execute("fictional-ack-lost",c));assertEquals(count,records());assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='job_external_resolution'",Integer.class));assertEquals(0,f.count("contents"));
    }
    @Test void FullLargeEvidenceUsesOrderedBoundShardsAndReplaysWithoutTruncation(){
        var c=command("succeeded");var payload=(ObjectNode)c.result().payload().deepCopy();var sections=((ObjectNode)payload.get("body")).putArray("sections");for(int n=0;n<2;n++)sections.addObject().put("heading","Fictional section").put("text","x".repeat(128000)).putArray("citation_ids").add("fictional-citation");var core=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",f.identity.inputHash());core.set("payload",payload);String resultHash=hashResult(core);var refs=new ArrayList<String>();for(int n=0;n<100;n++)refs.add("fictional-"+n+"-"+"e".repeat(48));var large=new JobAction(c.expectedRevision(),c.action(),"\u5BA1".repeat(2048),c.attemptId(),null,null,c.externalState(),refs,new ValidatedResult("1",f.identity.inputHash(),payload,resultHash),new Usage(null,null,null));var cmd=material(large);assertTrue(cmd.canonical().getBytes(StandardCharsets.UTF_8).length>262144);assertTrue(canonicalResult(cmd).getBytes(StandardCharsets.UTF_8).length<262144);var result=execute("fictional-large-review",cmd);assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE purpose='review'",Integer.class));assertTrue(f.jdbc.queryForObject("SELECT MAX(OCTET_LENGTH(payload_json)) FROM jobs WHERE purpose='review'",Long.class)<262144);assertEquals(result,execute("fictional-large-review",cmd));
    }
    @Test void OriginalReviewCorruptionPrecedesLaterLegitimateCommandHashConflict(){
        var c=material(command("succeeded"));execute("fictional-review-corrupt",c);var later=material(command("failed"));f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='review' AND part_no=1",new byte[32]);assertThrows(IllegalStateException.class,()->execute("fictional-review-corrupt",later));
    }
    @Test void SelfConsistentChangedResultPartCannotReplaceOriginalReviewEnvelope(){
        var c=material(command("succeeded"));execute("fictional-result-corrupt",c);var later=material(command("failed"));var changed=(ObjectNode)CanonicalJson.parse(canonicalResult(c));((ObjectNode)changed.get("payload").get("body")).put("title","Fictional replacement");f.jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE purpose='result'",JdbcFrozenInputs.canonical(changed),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,changed)));assertThrows(IllegalStateException.class,()->execute("fictional-result-corrupt",later));
    }
    @Test void OriginalNativeCorruptionIsRefusedBeforePersistingEvidence(){
        var c=material(command("succeeded"));long count=records();f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(IllegalStateException.class,()->execute("fictional-original-corrupt",c));assertEquals(count,records());assertEquals("unknown_external",state(f.identity.attemptId()));
    }
    @Test void WriterRequiresOriginalReadCommittedTransactionAndSemanticCallback(){
        var c=material(command("succeeded"));assertThrows(RuntimeException.class,()->ledger.resolve("fictional-no-transaction",c,f.clock.instant(),this::semantics));long count=records();assertThrows(IllegalArgumentException.class,()->f.writes.execute(f.action("fictional-invalid-semantic"),k->null,a->ledger.resolve(a.actionKey(),c,f.clock.instant(),(frame,raw)->{throw new IllegalArgumentException("Fictional invalid result");})));assertEquals(count,records());assertThrows(NullPointerException.class,()->f.writes.execute(f.action("fictional-no-semantic"),k->null,a->ledger.resolve(a.actionKey(),c,f.clock.instant(),null)));
    }
    void originalRetainedBeforeUnknown(){
        f.jdbc.update("UPDATE jobs SET state='running' WHERE id=?",f.identity.attemptId());
        f.writes.execute(f.action("fictional-before-unknown-retain"),k->null,c->{var state=new JdbcResultState(f.source);var result=ResultApplicationService.validate(f.store.input(),f.raw());state.retain(state.root(ResultJdbcStoreTest.ROOT),f.identity.attemptId(),result.resultHash(),f.raw(),f.clock.instant());return true;});
        f.jdbc.update("UPDATE jobs SET state='unknown_external' WHERE id=?",f.identity.attemptId());
    }
    @Test void SameOriginalRetainedResponseCanBeConfirmedWithoutReplacingAnyPartOrCall(){
        originalRetainedBeforeUnknown();var state=new JdbcResultState(f.source);var root=state.root(ResultJdbcStoreTest.ROOT);String retained=state.retained(root),request=original();long parts=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE purpose='result'",Long.class);var slot=new JdbcWorkerState(f.source).slot();
        var c=material(command("succeeded"));var result=execute("fictional-retained-confirm",c);assertTrue(result.rootResultRetained());assertEquals("succeeded",state(f.identity.attemptId()));assertEquals("EXTERNAL_RESULT_CONFIRMED",f.jdbc.queryForObject("SELECT application_state FROM jobs WHERE id=?",String.class,ResultJdbcStoreTest.ROOT));
        assertEquals(retained,state.retained(state.root(ResultJdbcStoreTest.ROOT)));assertEquals(root.resultJson(),state.root(ResultJdbcStoreTest.ROOT).resultJson());assertEquals(parts,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE purpose='result'",Long.class));assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(request,original());assertEquals(1,read().root().callCount());assertEquals(1,f.sends);assertEquals(0,f.count("contents"));assertEquals(result,execute("fictional-retained-confirm",c));
    }
    @Test void ExistingSuccessfulResponseCannotBeOverwrittenByUnknownFailureOrAnotherValidResponse(){
        originalRetainedBeforeUnknown();long count=records(),revision=read().root().revision();for(String outcome:List.of("unknown","failed"))rejected("identity_conflict",()->execute("fictional-retained-"+outcome,material(command(outcome))));
        var c=command("succeeded");var payload=(ObjectNode)c.result().payload().deepCopy();((ObjectNode)payload.get("body")).put("title","Fictional different valid response");var core=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",f.identity.inputHash());core.set("payload",payload);
        var changed=new JobAction(c.expectedRevision(),c.action(),c.reason(),c.attemptId(),null,null,c.externalState(),c.evidenceRefs(),new ValidatedResult("1",f.identity.inputHash(),payload,hashResult(core)),null);rejected("identity_conflict",()->execute("fictional-retained-replacement",material(changed)));assertEquals(count,records());assertEquals(revision,read().root().revision());assertEquals("unknown_external",state(f.identity.attemptId()));
    }
    @Test void RetainedPartAndRootAssociationsRemainMandatoryBeforeAnyConfirmation(){
        for(String damage:List.of("part","attempt_manifest","root_hash","root_manifest")){
            setup();originalRetainedBeforeUnknown();var c=material(command("succeeded"));long count=records(),revision=read().root().revision();
            switch(damage){case "part"->f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);case "attempt_manifest"->f.jdbc.update("UPDATE jobs SET result_json=NULL WHERE id=?",f.identity.attemptId());case "root_hash"->f.jdbc.update("UPDATE jobs SET result_hash=? WHERE id=?",new byte[32],ResultJdbcStoreTest.ROOT);case "root_manifest"->f.jdbc.update("UPDATE jobs SET result_json=NULL WHERE id=?",ResultJdbcStoreTest.ROOT);}
            assertThrows(IllegalStateException.class,()->execute("fictional-retained-damage-"+damage,c));assertEquals(count,records());assertEquals(revision,read().root().revision());assertEquals("unknown_external",state(f.identity.attemptId()));
        }
    }

}
