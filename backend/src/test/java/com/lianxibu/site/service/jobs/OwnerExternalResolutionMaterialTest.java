package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

class OwnerExternalResolutionMaterialTest {
    ResultJdbcStoreTest f;JdbcOwnerExternalAttemptReads original;
    static final Actor OWNER=new Actor("fictional-owner","owner");
    ResultJdbcStoreTest fixture(){var value=new ManualResultProofFixture();value.initialize(true);return value;}
    @BeforeEach void setup(){f=fixture();f.jdbc.execute("ALTER TABLE jobs ADD COLUMN error_code VARCHAR(64)");new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),f.clock,"fictional-epoch").release(f.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,f.action("fictional-external-unknown"));original=new JdbcOwnerExternalAttemptReads(f.source);}
    JdbcOwnerExternalAttemptReads.Frame read(){var tx=new TransactionTemplate(f.tx);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(s->original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));}
    JobAction command(String state){var raw=CanonicalJson.parse(f.raw());return new JobAction(read().root().revision(),"resolve_external","Fictional external verification",f.identity.attemptId(),null,null,state,List.of("fictional-evidence"),"succeeded".equals(state)?new ValidatedResult("1",f.identity.inputHash(),raw.get("payload"),raw.get("result_hash").textValue()):null,null);}
    OwnerExternalResolutionMaterial verify(JdbcOwnerExternalAttemptReads.Frame frame,JobAction command){return OwnerExternalResolutionMaterial.verify(OWNER,ResultJdbcStoreTest.ROOT,command,frame);}
    JdbcOwnerExternalAttemptReads.Frame altered(JdbcOwnerExternalAttemptReads.Frame frame,ObjectNode input){String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);var id=frame.identity();return new JdbcOwnerExternalAttemptReads.Frame(frame.root(),frame.generation(),JdbcFrozenInputs.canonical(input),frame.originalRootRevision(),frame.attemptNo(),frame.attemptState(),new JdbcResultState.Identity(id.rootId(),id.attemptId(),id.dispatchToken(),id.slotRevision(),id.fencing(),id.leaseUntil(),id.frozenRequestJson(),hash));}
    @Test void OriginalClaimRevisionAndCurrentCommandCasAreIndependent(){
        var frame=read();var slot=new JdbcWorkerState(f.source).slot();long count=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);var proof=verify(frame,command("succeeded"));
        assertEquals(frame.originalRootRevision(),proof.input().expectedRootRevision());assertEquals(frame.root().revision(),proof.evidence().expectedRevision());assertNotEquals(proof.input().expectedRootRevision(),proof.evidence().expectedRevision());assertEquals(f.identity.inputHash(),proof.input().inputHash());assertEquals("report",proof.evidence().result().kind());assertEquals("fictional-report",proof.context().contentId());assertEquals("OwnerExternalResolutionMaterial[redacted]",proof.toString());assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));assertEquals(0,f.count("contents"));assertEquals(1,f.sends);
    }
    @Test void FailedAndUnknownPreserveOriginalProofWithoutInventingResult(){for(String state:List.of("failed","unknown")){var proof=verify(read(),command(state));assertNull(proof.evidence().result());assertEquals(state,proof.evidence().externalState());assertTrue(CanonicalJson.parse(proof.evidence().canonical()).get("usage").isNull());}}
    @Test void EvenSelfConsistentInputDigestCannotReplaceNativeHeaderSemantics(){
        var frame=read();for(String field:List.of("schema_version","stage","generation","cutoff","coverage_state","missing")){
            var body=(ObjectNode)CanonicalJson.parse(frame.inputJson());switch(field){case "generation"->body.put(field,2);case "cutoff"->body.put(field,"2026-01-02T21:00:00.123456Z");case "missing"->body.putArray(field).add("fictional-missing");default->body.put(field,"fictional-wrong");}
            assertThrows(IllegalStateException.class,()->verify(altered(frame,body),command("unknown")),field);
        }
    }
    @Test void OriginalContextPublicMetadataAndFullReportProfileAreRequired(){
        var frame=read();for(String field:List.of(FrozenApplicationContext.FIELD,FrozenPublicMetadata.FIELD,ReportInputProfile.FIELD)){
            var body=(ObjectNode)CanonicalJson.parse(frame.inputJson());body.remove(field);assertThrows(IllegalStateException.class,()->verify(altered(frame,body),command("unknown")),field);
        }
        var bad=(ObjectNode)CanonicalJson.parse(frame.inputJson());((ObjectNode)bad.get(FrozenPublicMetadata.FIELD).get("citations").get(0)).put("id","fictional-outside");assertThrows(IllegalStateException.class,()->verify(altered(frame,bad),command("unknown")));
        var period=(ObjectNode)CanonicalJson.parse(frame.inputJson());((ObjectNode)period.get(FrozenApplicationContext.FIELD)).put("period_start","2025-12-30T16:00:00.000000Z").put("period_end","2025-12-31T16:00:00.000000Z");assertThrows(IllegalStateException.class,()->verify(altered(frame,period),command("unknown")));
    }
    @Test void BadClientResultRemainsParameterFailureAndIsNeverStorageSuccess(){
        var good=command("succeeded");var wrong=new ValidatedResult("1",f.identity.inputHash(),good.result().payload(),"0".repeat(64));var command=new JobAction(good.expectedRevision(),good.action(),good.reason(),good.attemptId(),null,null,good.externalState(),good.evidenceRefs(),wrong,null);assertThrows(IllegalArgumentException.class,()->verify(read(),command));
        assertThrows(IllegalStateException.class,()->verify(null,good));
    }
    @Test void UnauthorizedActorsAreRefusedBeforeReadingPrivateProof(){
        assertThrows(IdentityService.Rejected.class,()->OwnerExternalResolutionMaterial.verify(null,ResultJdbcStoreTest.ROOT,null,null));assertThrows(IdentityService.Rejected.class,()->OwnerExternalResolutionMaterial.verify(new Actor("fictional-user","member"),ResultJdbcStoreTest.ROOT,null,null));
    }
}
