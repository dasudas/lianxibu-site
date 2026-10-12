package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

class FrozenSourceUniverseServiceTest {
    GenerationJdbcStoreTest fixture;FrozenSourceUniverseService service;MemberUniverse.Universe original;
    int actions,reads;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() {
        fixture=fixture();fixture.fixture();original=FrozenSourceUniverseTest.universe(201,true);service=service();
        tx(()->{new JdbcJobRecords(fixture.source).ensureIntent(new JdbcJobRecords.Intent(FrozenSourceUniverseTest.OWNER,"fictional-complete-source",1,"news_dedup",LocalDate.of(2026,1,1)),fixture.clock.instant());return true;});
    }
    FrozenSourceUniverseService service(){return new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(fixture.source),run->{reads++;assertEquals(original.runId(),run);return original;},fixture.clock);}
    <T> T tx(java.util.function.Supplier<T> work){return fixture.writes.execute(new SharedWriteExecutor.ActionContext("fictional-universe-action-"+actions++,FrozenSourceUniverseTest.OWNER,fixture.HASH),k->null,a->work.get());}
    FrozenSourceUniverse.Reference freeze(){return tx(()->service.freeze(FrozenSourceUniverseTest.OWNER,original.runId()));}
    int count(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='snapshot_part'",Integer.class);}
    @Test void originalCompleteProofSurvivesRestartAndReplaysWithoutNewParts() {
        var reference=freeze();int count=count();assertTrue(count>2);
        assertEquals(original,tx(()->service().read(reference)));
        assertEquals(reference,freeze());assertEquals(count,count());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
    }
    @Test void emptyUniverseIsStoredWithoutInventedMembers() {
        original=FrozenSourceUniverseTest.universe(0,false);var reference=freeze();assertEquals(2,count());
        assertEquals(original,tx(()->service().read(reference)));assertEquals(0,original.accepted());
    }
    @Test void missingSurplusAndRehashedChunkCannotSatisfyOriginalReference() {
        var reference=freeze();String stamp=JdbcFrozenInputs.stableId("source-universe",original.runId());
        fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),reference.ownerId(),stamp);
        assertThrows(RuntimeException.class,()->tx(()->service().read(reference)));
        fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part'");freeze();
        fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",reference.ownerId(),stamp);
        assertThrows(RuntimeException.class,()->tx(()->service().read(reference)));
        fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part'");freeze();
        tx(()->{var node=CanonicalJson.parse("{}");new JdbcJobRecords(fixture.source).putPart(new JdbcJobRecords.Part("fictional-extra-part",reference.ownerId(),"input",stamp,999,"{}",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,node)),fixture.clock.instant());return true;});
        assertThrows(RuntimeException.class,()->tx(()->service().read(reference)));
    }
    @Test void exactOwnerStampAndRowIdentityAreRequired() {
        var reference=freeze();assertThrows(RuntimeException.class,()->tx(()->service.freeze("FICTIONAL-universe-owner",original.runId())));
        assertThrows(RuntimeException.class,()->tx(()->service.freeze("fictional-missing-owner",original.runId())));
        fixture.jdbc.update("UPDATE jobs SET id='fictional-wrong-row' WHERE record_kind='snapshot_part' AND part_no=1");
        assertThrows(RuntimeException.class,()->tx(()->service().read(reference)));
        assertThrows(RuntimeException.class,()->tx(()->service().read(new FrozenSourceUniverse.Reference(reference.ownerId(),"fictional-other-run",reference.universeHash(),reference.indexHash()))));
    }
    @Test void changedSameCountOriginalCannotOverwriteAndFailuresRollBack() {
        var reference=freeze();int count=count();var previous=original;var members=new ArrayList<>(original.members());var member=members.getFirst();
        members.set(0,new MemberUniverse.Member(member.itemId(),member.memberId(),member.sourceItemId(),"fictional-observation-replaced",member.payloadHash(),null,member.partNo(),member.terminalState()));
        original=new MemberUniverse.Universe(original.runId(),original.sourceId(),original.manifestHash(),original.parts(),members,original.accepted(),original.excluded());
        assertThrows(RuntimeException.class,this::freeze);assertEquals(count,count());
        original=previous;assertEquals(previous,tx(()->service().read(reference)));
        fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part'");
        assertThrows(RuntimeException.class,()->tx(()->{service.freeze(reference.ownerId(),reference.runId());throw new IllegalStateException("fictional outer failure");}));
        assertEquals(0,count());assertEquals(reference,freeze());
    }
    @Test void sizeGuardAndNonWriterTransactionsRefuseAccess() {
        var reference=freeze();assertThrows(RuntimeException.class,()->service.read(reference));assertThrows(RuntimeException.class,()->service.freeze(reference.ownerId(),reference.runId()));
        var readOnly=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.source));readOnly.setReadOnly(true);
        assertThrows(RuntimeException.class,()->readOnly.execute(a->service.read(reference)));
        fixture.jdbc.update("UPDATE jobs SET payload_json=? WHERE record_kind='snapshot_part' AND part_no=1","{\"oversize\":\""+"x".repeat(262145)+"\"}");
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));
    }

    @Test void aRehashedReplacementDuringTheWriteCannotCommitTheOriginalUniverse() {
        var storage=org.mockito.Mockito.spy(new JdbcFrozenSourceUniverses(fixture.source));
        org.mockito.Mockito.doAnswer(call->{call.callRealMethod();if((int)call.getArgument(2)==1){String owner=call.getArgument(0),stamp=call.getArgument(1);fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),owner,stamp);}return null;}).when(storage).put(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        service=new FrozenSourceUniverseService(storage,run->original,fixture.clock);assertThrows(RuntimeException.class,this::freeze);assertEquals(0,count());service=service();assertNotNull(freeze());
    }
    @Test void wrongNativeBlockIdentityDuringTheWriteRollsBackEveryOriginalPart() {
        var storage=org.mockito.Mockito.spy(new JdbcFrozenSourceUniverses(fixture.source));
        org.mockito.Mockito.doAnswer(call->{call.callRealMethod();if((int)call.getArgument(2)==1){String owner=call.getArgument(0),stamp=call.getArgument(1);fixture.jdbc.update("UPDATE jobs SET id='fictional-substituted-block' WHERE owner_job_id=? AND stamp_id=? AND part_no=1",owner,stamp);}return null;}).when(storage).put(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        service=new FrozenSourceUniverseService(storage,run->original,fixture.clock);assertThrows(RuntimeException.class,this::freeze);assertEquals(0,count());service=service();assertNotNull(freeze());
    }
}
