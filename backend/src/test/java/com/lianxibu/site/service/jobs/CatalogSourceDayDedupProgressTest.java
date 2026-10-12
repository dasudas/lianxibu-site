package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.CatalogSourceProofGraph;
import com.lianxibu.site.domain.sources.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Status;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual full native source proof; registration is an explicit API test double. */
class CatalogSourceDayDedupProgressTest extends CatalogVersionSourceFinalTest {
    final LocalDate day=LocalDate.of(2026,1,1);final Instant registered=Instant.parse("2026-01-01T16:10:00Z"),sealed=registered.plusSeconds(5),reserved=sealed.plusSeconds(5);
    SourceDayRunRegistration.Api runs;SourceDayRunRegistration.Request request;CatalogSourceDayDedupProgressService service;boolean blocked;
    @Override void graph(){if(!blocked){super.graph();return;}graph=new CatalogSourceProofGraph(f.source,f.tx,f.clock,()->new FrozenGenerationSettings(1,List.of(),List.of("Fictional")),()->true,f.category);collections=graph.collections();universes=graph.universes();plans=graph.plans();preparation=graph.preparation();}
    void install(String category){
        terminal(category);request=new SourceDayRunRegistration.Request(day,"a".repeat(64),registered,new SourceDayRoster.RawSource("fictional-source",category,2,1,"production","enabled",registered.plusSeconds(43200),true,true,"b".repeat(64)),RUN);
        runs=mock(SourceDayRunRegistration.Api.class);when(runs.lookup(request)).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new SourceDayRunRegistration.Reservation(request,1,reserved,request.hash());});service=provider(category);
    }
    CatalogSourceDayDedupProgressService provider(String category){return new CatalogSourceDayDedupProgressService(runs,graph.plans(),graph.finalizer(),graph.observer(),f.tx,category);}
    Instant now(){return f.clock.instant().plusSeconds(60);}
    SourceDedupBarrier.Progress progress(){return service.read(request,sealed,now());}
    Instant complete(){assertEquals(Status.APPLIED,apply().status());assertNotNull(graph.finalizer().finish(reference));return graph.observer().capture(reference);}
    @Test void FourActualCatalogFinalsRequirePermanentFinalAndTheirFirstObservation(){
        for(String category:List.of("agent","skill","workflow","model")){
            install(category);assertNull(progress());assertEquals(Status.APPLIED,apply().status());assertNull(progress());assertNotNull(graph.finalizer().finish(reference));assertNull(progress());Instant at=graph.observer().capture(reference);int rows=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);var result=progress();
            assertEquals(SourceDedupBarrier.State.COMPLETE,result.state());assertEquals(category,result.category());assertEquals(request.runId(),result.runId());assertEquals(request.source().effectiveConfigHash(),result.configHash());assertEquals(1,result.configRevision());assertEquals(at,result.availableAt());assertEquals(committed().result().hash(),result.proofHash());assertEquals(result,provider(category).read(request,sealed,now()));assertEquals(rows,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertNull(service.read(request,sealed,at.minusNanos(1000)));assertFalse(result.toString().contains("fictional-source"));
            var cohort=SourceDedupBarrier.freeze(category,day,registered,List.of(new SourceDedupBarrier.Member(request.source().id(),SourceDedupBarrier.Inclusion.ENABLED,1L,request.source().effectiveConfigHash(),RUN,1L)));var closure=SourceDedupBarrier.close(cohort,now(),List.of(result));assertEquals("late",closure.entries().getFirst().reason());assertEquals(SourceDedupBarrier.Decision.MISSING,closure.entries().getFirst().decision());assertEquals(2,f.sends);
        }
    }
    @Test void NativeFinalPartialCoverageIsNeverPromotedToComplete(){blocked=true;install("skill");complete();assertEquals("partial",committed().result().coverageState());assertEquals(SourceDedupBarrier.State.PARTIAL,progress().state());assertFalse(committed().result().missing().isEmpty());}
    @Test void WrongRegistrationRoleDayAndDamagedOriginalFinalCannotBecomeEmptySuccess(){
        install("model");complete();when(runs.lookup(request)).thenReturn(null);assertNull(progress());when(runs.lookup(request)).thenReturn(new SourceDayRunRegistration.Reservation(request,1,registered,request.hash()));assertThrows(RuntimeException.class,this::progress);
        var other=new SourceDayRunRegistration.Request(day,request.rosterHash(),registered,request.source(),"fictional-other-run");when(runs.lookup(request)).thenReturn(new SourceDayRunRegistration.Reservation(other,1,reserved,other.hash()));assertThrows(RuntimeException.class,this::progress);
        when(runs.lookup(request)).thenReturn(new SourceDayRunRegistration.Reservation(request,1,reserved,request.hash()));var wrong=new SourceDayRunRegistration.Request(day,request.rosterHash(),registered,request.source(),"fictional-other-run");when(runs.lookup(wrong)).thenReturn(new SourceDayRunRegistration.Reservation(wrong,1,reserved,wrong.hash()));assertThrows(RuntimeException.class,()->service.read(wrong,sealed,now()));
        var proof=committed();f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],proof.receipt().position().receiptId());assertThrows(RuntimeException.class,this::progress);assertEquals(2,f.sends);verify(runs,never()).reserve(any());
    }
    @Test void ClosedCategoryAndOutsideMicrosecondWindowAreMandatoryAndConstructionDoesNoIo(){
        var api=mock(SourceDayRunRegistration.Api.class);var plans=mock(FrozenSourceStagePlanService.class);var finals=mock(CatalogSourceFinalizationService.class);var observer=mock(CatalogSourceFinalAvailabilityService.class);var manager=mock(PlatformTransactionManager.class);
        for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogSourceDayDedupProgressService(api,plans,finals,observer,manager,category));verifyNoInteractions(api,plans,finals,observer,manager);assertThrows(RuntimeException.class,()->new CatalogSourceDayDedupProgressService(api,plans,finals,observer,manager,"news_tech"));
        install("workflow");assertThrows(RuntimeException.class,()->service.read(request,sealed.minusSeconds(6),now()));assertThrows(RuntimeException.class,()->service.read(request,sealed.plusNanos(1),now()));assertThrows(RuntimeException.class,()->service.read(request,sealed,now().plusNanos(1)));assertThrows(RuntimeException.class,()->service.read(request,SourceDedupBarrier.deadline(day),now()));assertThrows(RuntimeException.class,()->write(this::progress));assertThrows(RuntimeException.class,()->provider("agent").read(request,sealed,now()));verify(runs,never()).lookup(any());verify(runs,never()).reserve(any());
    }
}
