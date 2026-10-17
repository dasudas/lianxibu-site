package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import tools.jackson.databind.node.ObjectNode;

class FrozenContentProofReuseTest extends FrozenContentVersionSnapshotTest {
    FrozenContentVersionService versions;
    @BeforeEach void consumer(){
        store=spy(store);
        versions=new FrozenContentVersionService(store);
        verifyNoInteractions(store);
    }
    @Test void SameNativeReadSnapshotReusesOnlyImmutableProofAndReturnsIndependentReferenceNodes(){
        rr(()->{
            var first=versions.find(version).orElseThrow();
            ((ObjectNode)first.context().referenceTargets().getFirst()).put("id","fictional-changed-copy");
            var again=versions.find(version).orElseThrow();
            assertEquals(first.bodyJson(),again.bodyJson());assertEquals(first.snapshotJson(),again.snapshotJson());
            assertEquals(first.inputHash(),again.inputHash());assertEquals(first.context().json(),again.context().json());
            assertEquals("fictional-citation",again.context().referenceTargets().getFirst().path("id").asText());
            verify(store,times(1)).read(version);verify(store,times(2)).observedAt(version,first.inputHash());return true;
        });
        assertEquals(1,fixture.sends);
    }
    @Test void RequiresNewAndCompletionNeverBorrowAnotherTransactionProof(){
        rr(()->{
            versions.find(version);versions.find(version);verify(store,times(1)).read(version);
            read(true,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRES_NEW,()->{
                versions.find(version);versions.find(version);verify(store,times(2)).read(version);return true;
            });
            versions.find(version);verify(store,times(2)).read(version);return true;
        });
        rr(()->{versions.find(version);versions.find(version);return true;});verify(store,times(3)).read(version);
        assertThrows(IllegalStateException.class,()->rr(()->{versions.find(version);throw new IllegalStateException("fictional rollback");}));
        rr(()->versions.find(version));verify(store,times(5)).read(version);
    }
    @Test void WriterAndReadCommittedAlwaysRevalidateAndRejectNativeDamage(){
        for(boolean readonly:new boolean[]{true,false})read(readonly,TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.PROPAGATION_REQUIRED,()->{
            clearInvocations(store);versions.find(version);versions.find(version);verify(store,times(2)).read(version);return true;
        });
        read(false,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRED,()->{
            clearInvocations(store);versions.find(version);versions.find(version);verify(store,times(2)).read(version);
            new org.springframework.jdbc.core.JdbcTemplate(counted).update("UPDATE jobs SET action_payload_hash=? WHERE target_type='result_application'",new byte[32]);
            assertThrows(RuntimeException.class,()->versions.find(version));return true;
        });
    }
    @Test void MissingAndInvalidProofsNeverPopulateTheConsumerSnapshot(){
        var missing=new ContentVersionKey(version.contentKind(),"fictional-missing",version.revision(),version.bodyHash(),version.inputJobId());
        var proof=store.read(version);clearInvocations(store);
        rr(()->{
            assertTrue(versions.find(missing).isEmpty());assertTrue(versions.find(missing).isEmpty());verify(store,times(2)).read(missing);
            var corrupt=new JdbcFrozenContentVersions.Proof(proof.versionJson(),"0".repeat(64),proof.snapshotJson(),proof.resultJson(),proof.bodyJson(),proof.attemptId(),proof.recordedAt());
            doReturn(corrupt).when(store).read(version);
            assertThrows(RuntimeException.class,()->versions.find(version));assertThrows(RuntimeException.class,()->versions.find(version));
            doCallRealMethod().when(store).read(version);assertTrue(versions.find(version).isPresent());
            verify(store,times(3)).read(version);return true;
        });
    }
    @Test void CachedImmutableProofNeverHidesAvailabilityReadFailureOrKeepsAnOldTime(){
        rr(()->{
            var first=versions.find(version).orElseThrow();assertNull(first.availableAt());
            doThrow(new IllegalStateException("fictional unavailable observation")).when(store).observedAt(version,first.inputHash());
            assertThrows(IllegalStateException.class,()->versions.find(version));
            doReturn(fixture.clock.instant()).when(store).observedAt(version,first.inputHash());
            assertEquals(fixture.clock.instant(),versions.findAt(version,fixture.clock.instant()).orElseThrow().availableAt());
            assertTrue(versions.findAt(version,fixture.clock.instant().minusNanos(1000)).isEmpty());
            verify(store,times(1)).read(version);verify(store,times(4)).observedAt(version,first.inputHash());return true;
        });
    }
    ContentVersionKey archive(int revision,String text){
        String body="{\"fictional\":\""+text+"\"}";
        var key=new ContentVersionKey(version.contentKind(),version.contentId(),revision,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(body)),version.inputJobId());
        String input=store.read(version).inputHash();
        fixture.writes.execute(fixture.action("fictional-reuse-archive-"+revision),k->null,a->{new JdbcContentReviewEvidence(fixture.source).archive(key,body,input,"fictional-owner",fixture.clock.instant());return true;});
        return key;
    }
    @Test void OversizedCompleteBodyBypassesReuseWithoutTruncation(){
        var large=archive(2,"F".repeat(600000));clearInvocations(store);
        rr(()->{
            var first=versions.find(large).orElseThrow();var again=versions.find(large).orElseThrow();
            assertEquals(600000,CanonicalJson.parse(first.bodyJson()).path("fictional").asText().length());
            assertEquals(large.bodyHash(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(again.bodyJson())));
            assertEquals(first.bodyJson(),again.bodyJson());assertEquals(first.snapshotJson(),again.snapshotJson());assertEquals(first.inputHash(),again.inputHash());assertEquals(first.context().json(),again.context().json());assertEquals(first.availableAt(),again.availableAt());verify(store,times(2)).read(large);return true;
        });
    }
    @Test void NinthCompleteVersionBypassesTheEntryLimit(){
        var snapshot=(ObjectNode)CanonicalJson.parse(store.read(version).snapshotJson());
        ((ObjectNode)snapshot.path("members").get(0).path("body")).put("text","Fictional concise material");
        String input=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot),owner="fictional-small-root";
        fixture.writes.execute(fixture.action("fictional-small-input"),k->null,a->{
            var records=new JdbcJobRecords(fixture.source);
            records.ensureIntent(new JdbcJobRecords.Intent("fictional-small-intent","fictional-small-key",1,"daily_report",java.time.LocalDate.of(2026,1,1)),fixture.clock.instant());
            records.createRoot(new JdbcJobRecords.Root(owner,"fictional-small-intent","fictional-small-key",1,0,"daily_report",1),fixture.clock.instant());
            new JdbcFrozenInputs(fixture.source).save(owner,JdbcFrozenInputs.canonical(snapshot),input,fixture.clock.instant());return true;
        });
        var keys=new ArrayList<ContentVersionKey>();
        for(int i=2;i<=10;i++){
            String body="{\"fictional\":\"Fictional body "+i+"\"}";
            var key=new ContentVersionKey(version.contentKind(),version.contentId(),i,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(body)),owner);keys.add(key);
            fixture.writes.execute(fixture.action("fictional-small-archive-"+i),k->null,a->{new JdbcContentReviewEvidence(fixture.source).archive(key,body,input,"fictional-owner",fixture.clock.instant());return true;});
        }
        clearInvocations(store);
        rr(()->{
            for(var key:keys)versions.find(key).orElseThrow();
            for(var key:keys)versions.find(key).orElseThrow();
            for(int i=0;i<8;i++)verify(store,times(1)).read(keys.get(i));verify(store,times(2)).read(keys.get(8));return true;
        });
    }
    @Test void DifferentIssuerAndUnboundThreadCannotBorrowTheOuterProof(){
        var other=new FrozenContentVersionService(store);
        rr(()->{
            versions.find(version);versions.find(version);other.find(version);other.find(version);verify(store,times(2)).read(version);
            try(var executor=Executors.newSingleThreadExecutor()){
                assertTrue(executor.submit(()->{versions.find(version);return versions.find(version).isPresent();}).get(10,TimeUnit.SECONDS));
            }catch(Exception failure){throw new AssertionError(failure);}
            verify(store,times(4)).read(version);versions.find(version);verify(store,times(4)).read(version);return true;
        });
    }
}
