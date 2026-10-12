package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

class SourceDayCollectionAdmissionServiceTest {
    SourceDayRunCoordinatorServiceTest fixture;SourceDayCollectionAdmissionService provider;
    @BeforeEach void setup(){fixture=new SourceDayRunCoordinatorServiceTest();fixture.setup();provider=new SourceDayCollectionAdmissionService(fixture.seals);}
    @Test void realCompleteOriginalSealProducesFullCountsAndEnabledRequests(){
        var original=fixture.seals.seal(fixture.fixture.day);var result=provider.read(fixture.fixture.day);assertEquals(3,result.sourceCount());assertEquals(1,result.unassignedCount());assertEquals(2,result.requests().size());assertEquals(2L,result.cohortCounts().get("agent"));assertEquals(5,result.cohortCounts().size());assertEquals(original.plan().bundleHash(),result.bundleHash());assertEquals(original.plan().directory().evidence().hash(),result.directoryHash());assertEquals(original.sealedAt(),result.sealedAt());verifyNoInteractions(fixture.runs);
    }
    @Test void absentIsNotEmptyAndReadDoesNotCaptureOrReadClock(){
        var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var reader=new SourceDayCollectionAdmissionService(fixture.fixture.service(input,clock));assertNull(reader.read(fixture.fixture.day));verifyNoInteractions(input,clock);
        var empty=fixture.fixture.service(()->fixture.fixture.evidence(0),Clock.fixed(fixture.fixture.now,ZoneOffset.UTC));empty.seal(fixture.fixture.day);var view=new SourceDayCollectionAdmissionService(empty).read(fixture.fixture.day);assertEquals(0,view.sourceCount());assertEquals(5,view.cohortCounts().size());assertTrue(view.requests().isEmpty());
    }
    @Test void originalRestartViewIgnoresCurrentInputAndDamagedOriginalCannotBecomeSuccess(){
        var first=fixture.seals.seal(fixture.fixture.day);var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var reader=new SourceDayCollectionAdmissionService(fixture.fixture.service(input,clock));assertEquals(first.plan().requests(),reader.read(fixture.fixture.day).requests());verifyNoInteractions(input,clock);
        fixture.fixture.fixture.jdbc.update("UPDATE jobs SET action_payload='{}' WHERE record_kind='disposition'");assertThrows(RuntimeException.class,()->reader.read(fixture.fixture.day));verifyNoInteractions(input,clock);assertEquals(1,fixture.fixture.dispositions());
    }
    @Test void missingProviderAndCallerOwnedTransactionFailClosed(){
        assertThrows(RuntimeException.class,()->new SourceDayCollectionAdmissionService(null));var tx=new TransactionTemplate(fixture.fixture.manager);assertThrows(IllegalStateException.class,()->tx.execute(s->provider.read(fixture.fixture.day)));verifyNoInteractions(fixture.runs);
    }
}
