package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete native registration, source result, whole INPUT, worker, content result and post-commit observation. */
class CatalogDayAnalysisCompletionTest {
    boolean mysql(){return false;}
    record Fixture(CatalogDayAnalysisResultTest helper,CatalogDayAnalysisResultTest.Built built,
                   CatalogPreviousAnalysisTest.Fixture f,LocalDate day,Clock clock,
                   ContentVersionKey version,CatalogDayAnalysisCompletionService service){
        String entity(){return built.application().input().targetEntityId();}
    }
    Fixture fixture(String category,boolean alias){
        var helper=new CatalogDayAnalysisResultTest(){@Override boolean mysql(){return CatalogDayAnalysisCompletionTest.this.mysql();}};
        var built=helper.fixture(category,alias);var nativeData=built.data().data().data().data();var f=nativeData.f().f;
        var result=helper.resultOf(built,"completion");assertEquals(ResultApplicationService.Status.APPLIED,result.status());
        var version=ContentVersionKey.fromJson(CanonicalJson.parse(f.jdbc.queryForObject("SELECT content_key_json FROM contents WHERE id=?",String.class,result.contentId())));
        return new Fixture(helper,built,f,nativeData.outer().day,built.data().clock(),version,new CatalogDayAnalysisCompletionService(f.source,f.tx,built.data().clock(),category));
    }
    Instant observe(Fixture f,Clock clock){return new VersionAvailabilityService(new FrozenContentVersionService(new JdbcFrozenContentVersions(f.f().source)),new JdbcVersionAvailability(f.f().source),f.f().writes,f.f().tx,clock).capture(f.version());}
    @Test void FourActualCategoriesRequirePostCommitObservationAndKeepExactFrozenBodyAcrossRestartAndCurrentDeletion(){
        for(String category:List.of("agent","skill","workflow","model")){
            var f=fixture(category,false);var at=f.clock().instant().plusSeconds(5);assertNull(f.service().find(f.day(),f.entity(),f.version(),at));
            assertEquals(at,observe(f,Clock.fixed(at,ZoneOffset.UTC)));assertNull(f.service().find(f.day(),f.entity(),f.version(),at.minusNanos(1000)));
            var complete=f.service().find(f.day(),f.entity(),f.version(),at);assertNotNull(complete);assertEquals(f.version(),complete.version());assertEquals(at,complete.availableAt());assertEquals(1L,complete.reservation().generation());assertEquals(1,complete.reservation().members().size());assertEquals(1,CanonicalJson.parse(complete.bodyJson()).get("citation_ids").size());
            int jobs=f.f().jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
            for(String table:List.of("source_run_items","source_runs","sources","analyzed_entities","contents"))f.f().jdbc.execute("DROP TABLE "+table);
            var restarted=new CatalogDayAnalysisCompletionService(f.f().source,f.f().tx,Clock.fixed(at.plusSeconds(100),ZoneOffset.UTC),category);
            var replay=restarted.find(f.day(),f.entity(),f.version(),at);assertNotNull(replay);assertEquals(complete.reservation().json(),replay.reservation().json());assertEquals(complete.version(),replay.version());assertEquals(complete.inputHash(),replay.inputHash());assertEquals(complete.bodyJson(),replay.bodyJson());assertEquals(complete.snapshotJson(),replay.snapshotJson());assertEquals(complete.availableAt(),replay.availableAt());assertEquals(jobs,f.f().jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f().sends);
        }
    }
    @Test void VersionTwoKeepsBothOriginalAliasesAndRejectsOtherRootOrCategory(){
        var f=fixture("agent",true);var at=observe(f,Clock.fixed(f.clock().instant().plusSeconds(5),ZoneOffset.UTC));
        var complete=f.service().find(f.day(),f.entity(),f.version(),at);assertEquals(2L,complete.reservation().generation());assertEquals(2,complete.reservation().members().size());assertEquals(2,CanonicalJson.parse(complete.bodyJson()).get("citation_ids").size());
        var other=new ContentVersionKey("agent",f.version().contentId(),f.version().revision(),f.version().bodyHash(),CatalogPreviousAnalysisTest.Fixture.ROOT);
        assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),other,at));
        assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),new ContentVersionKey("model",f.version().contentId(),f.version().revision(),f.version().bodyHash(),f.version().inputJobId()),at));
    }
    @Test void DamagedOriginalObservationRejectsInsteadOfUsingCurrentBodyOrTimestamp(){
        var f=fixture("skill",false);var at=observe(f,Clock.fixed(f.clock().instant().plusSeconds(5),ZoneOffset.UTC));
        assertNotNull(f.service().find(f.day(),f.entity(),f.version(),at));
        f.f().jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='content_availability' AND action_key_hash=UNHEX(?)",new byte[32],JdbcResultState.sha(JdbcVersionAvailability.key(f.version())));
        assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),f.version(),at));assertEquals(2,f.f().sends);
    }
    @Test void ValidlyHashedButBackdatedObservationCannotProveOriginalAppliedResultAtAnEarlierCutoff(){
        var f=fixture("workflow",false);var before=f.clock().instant().minusSeconds(1000);observe(f,Clock.fixed(before,ZoneOffset.UTC));
        assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),f.version(),f.clock().instant()));assertEquals(2,f.f().sends);
    }
    @Test void ColdConstructionIsZeroIoAndBorrowedLookupRejectsOutsideOrWriterBeforeAnyIo(){
        var source=mock(javax.sql.DataSource.class);var tx=mock(PlatformTransactionManager.class);var service=new CatalogDayAnalysisCompletionService(source,tx,Clock.systemUTC(),"agent");
        verifyNoInteractions(source,tx);assertThrows(RuntimeException.class,()->service.read(LocalDate.of(2026,1,1),"fictional-entity",null,Instant.EPOCH));
        verifyNoInteractions(source,tx);assertThrows(RuntimeException.class,()->new CatalogDayAnalysisCompletionService(source,tx,Clock.systemUTC(),"other"));
        var f=fixture("model",false);var writer=new TransactionTemplate(f.f().tx);writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writer.execute(s->{assertThrows(RuntimeException.class,()->f.service().read(f.day(),f.entity(),f.version(),f.clock().instant()));assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),f.version(),f.clock().instant()));return null;});
    }
}
