package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SourceDayRunClaimAdmissionServiceTest {
    JdbcSourceDayRunRegistrationsTest fixture;SourceDayCollectionAdmission.Api provider;SourceDayRunClaimAdmissionService consumer;
    JdbcSourceDayRunRegistrationsTest fixture(){return new JdbcSourceDayRunRegistrationsTest();}
    @BeforeEach void setup()throws Exception{fixture=fixture();fixture.setup();provider=mock(SourceDayCollectionAdmission.Api.class);consumer=new SourceDayRunClaimAdmissionService(provider,fixture.store,fixture.tx.getTransactionManager());when(provider.read(fixture.day)).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return view(fixture.reserved);});}
    SourceDayCollectionAdmission.SealedDay view(Instant sealed){return new SourceDayCollectionAdmission.SealedDay(fixture.day,fixture.request.rosterHash(),"b".repeat(64),fixture.registered,sealed,2,1,Map.of("news_tech",0L,"agent",1L,"skill",0L,"workflow",0L,"model",0L),List.of(fixture.request));}
    SourceDayRunClaimAdmissionService.Prepared prepare(){return consumer.prepare(Set.of(fixture.day),Set.of(fixture.request.source().id()));}
    @Test void actualOriginalRunAndReceiptAreRequiredAndOnlyVerifiedTupleIsPrepared(){
        var missing=prepare();assertTrue(missing.runIds().isEmpty());assertEquals(Set.of(fixture.request.runId()),missing.unverifiedRunIds());assertEquals(Boolean.FALSE,fixture.tx.execute(s->consumer.allows(missing,fixture.request.runId())));fixture.reserve();var prepared=prepare();assertEquals(List.of(fixture.request.runId()),prepared.runIds());assertTrue(prepared.unverifiedRunIds().isEmpty());assertEquals(Boolean.TRUE,fixture.tx.execute(s->consumer.allows(prepared,fixture.request.runId())));assertEquals(Boolean.FALSE,fixture.tx.execute(s->consumer.allows(prepared,"fictional-other")));assertThrows(UnsupportedOperationException.class,()->prepared.runIds().clear());assertFalse(prepared.toString().contains(fixture.request.runId()));
        fixture.sql.update("UPDATE source_runs SET revision=9,delivery_state='complete'");fixture.sql.execute("DROP TABLE sources");assertEquals(Boolean.TRUE,fixture.tx.execute(s->consumer.allows(prepared,fixture.request.runId())));
    }
    @Test void absentOrUnrequestedFullDayCannotBecomeSuccessfulPermission(){
        doReturn(null).when(provider).read(fixture.day);var missing=prepare();assertTrue(missing.runIds().isEmpty());assertEquals(Boolean.FALSE,fixture.tx.execute(s->consumer.allows(missing,fixture.request.runId())));
        assertTrue(consumer.prepare(Set.of(),Set.of(fixture.request.source().id())).runIds().isEmpty());doReturn(view(fixture.reserved)).when(provider).read(fixture.day);assertTrue(consumer.prepare(Set.of(fixture.day),Set.of("fictional-unassigned")).runIds().isEmpty());
    }
    @Test void earlyRunAndDamagedActualReceiptNeverGrantPermission(){
        fixture.reserve();doReturn(view(fixture.reserved.plusNanos(1000))).when(provider).read(fixture.day);var early=prepare();assertTrue(early.runIds().isEmpty());assertEquals(Set.of(fixture.request.runId()),early.unverifiedRunIds());assertEquals(Boolean.FALSE,fixture.tx.execute(s->consumer.allows(early,fixture.request.runId())));
        doReturn(view(fixture.reserved)).when(provider).read(fixture.day);var prepared=prepare();fixture.sql.update("UPDATE source_runs SET config_version=4");assertThrows(RuntimeException.class,()->fixture.tx.execute(s->consumer.allows(prepared,fixture.request.runId())));
        fixture.sql.update("UPDATE source_runs SET config_version=3");fixture.sql.update("UPDATE request_receipts SET business_hash=?",new byte[32]);assertThrows(RuntimeException.class,()->fixture.tx.execute(s->consumer.allows(prepared,fixture.request.runId())));
    }
    @Test void wrongTransactionAndForeignPreparedFailBeforeProofSql(){
        var prepared=prepare();assertThrows(IllegalStateException.class,()->consumer.allows(prepared,fixture.request.runId()));assertThrows(IllegalStateException.class,()->fixture.reads.execute(s->consumer.allows(prepared,fixture.request.runId())));assertThrows(IllegalStateException.class,()->fixture.tx.execute(s->prepare()));
        var other=new SourceDayRunClaimAdmissionService(provider,fixture.store,fixture.tx.getTransactionManager());assertThrows(RuntimeException.class,()->fixture.tx.execute(s->other.allows(prepared,fixture.request.runId())));
    }
    @Test void capacitiesAndWrongProviderDateCannotTruncateOrAdmit(){
        var dates=new HashSet<LocalDate>();for(int i=0;i<33;i++)dates.add(fixture.day.plusDays(i));assertThrows(RuntimeException.class,()->consumer.prepare(dates,Set.of(fixture.request.source().id())));
        var sources=new HashSet<String>();for(int i=0;i<1001;i++)sources.add("fictional-"+i);assertThrows(RuntimeException.class,()->consumer.prepare(Set.of(fixture.day),sources));
        var otherDay=fixture.day.plusDays(1);var empty=new SourceDayCollectionAdmission.SealedDay(otherDay,"a".repeat(64),"b".repeat(64),fixture.registered.plusSeconds(86400),fixture.reserved.plusSeconds(86400),0,0,Map.of("news_tech",0L,"agent",0L,"skill",0L,"workflow",0L,"model",0L),List.of());doReturn(empty).when(provider).read(fixture.day);assertThrows(RuntimeException.class,this::prepare);
    }
    @Test void invalidOriginalIsExcludedButSqlFailureAndUnconfiguredReadScopeCannotBecomeEmptyPermission(){
        fixture.reserve();fixture.sql.update("UPDATE source_runs SET config_version=4");var invalid=prepare();assertTrue(invalid.runIds().isEmpty());assertEquals(Set.of(fixture.request.runId()),invalid.unverifiedRunIds());
        fixture.sql.execute("DROP TABLE request_receipts");assertThrows(org.springframework.dao.DataAccessException.class,this::prepare);
        assertThrows(IllegalStateException.class,()->new SourceDayRunClaimAdmissionService(provider,fixture.store).prepare(Set.of(fixture.day),Set.of(fixture.request.source().id())));
    }
    @Test void oneUnregisteredMemberDoesNotPreventAnotherVerifiedMemberAndOriginalCountStaysComplete(){
        fixture.reserve();var raw=fixture.request.source();var other=new com.lianxibu.site.domain.sources.SourceDayRoster.RawSource("fictional-other",raw.category(),raw.sourceRevision(),raw.configRevision(),raw.activationStage(),raw.legacyState(),raw.configValidUntil(),raw.draftPresent(),raw.effectivePresent(),raw.effectiveConfigHash());
        var request=new com.lianxibu.site.domain.sources.SourceDayRunRegistration.Request(fixture.day,fixture.request.rosterHash(),fixture.registered,other,"fictional-other-run");
        var complete=new SourceDayCollectionAdmission.SealedDay(fixture.day,fixture.request.rosterHash(),"b".repeat(64),fixture.registered,fixture.reserved,2,0,Map.of("news_tech",0L,"agent",2L,"skill",0L,"workflow",0L,"model",0L),List.of(fixture.request,request));doReturn(complete).when(provider).read(fixture.day);
        var prepared=consumer.prepare(Set.of(fixture.day),Set.of(raw.id(),other.id()));assertEquals(List.of(fixture.request.runId()),prepared.runIds());assertEquals(Set.of(request.runId()),prepared.unverifiedRunIds());assertEquals(2,complete.requests().size());assertEquals(2L,complete.cohortCounts().get("agent"));assertEquals(Boolean.TRUE,fixture.tx.execute(s->consumer.allows(prepared,fixture.request.runId())));assertEquals(Boolean.FALSE,fixture.tx.execute(s->consumer.allows(prepared,request.runId())));
    }
}
