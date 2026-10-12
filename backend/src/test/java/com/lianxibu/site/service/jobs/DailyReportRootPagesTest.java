package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual native original daily report and independently prepared queued roots; source/model facts are fictional. */
class DailyReportRootPagesTest {
    ReportOriginalCompletionTest h;JdbcDailyReportRootPages pages;
    ReportOriginalCompletionTest fixture(){return new ReportOriginalCompletionTest();}
    static final LocalDate DAY=LocalDate.parse("2026-01-01");
    @BeforeEach void setup(){h=fixture();h.setup();pages=new JdbcDailyReportRootPages(h.h.f.source);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(h.h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    void queued(int n,LocalDate day,String key){ReportOriginalCompletionTest.utc(()->{String id=JdbcFrozenInputs.stableId("fictional-daily-root",Integer.toString(n));var c=new Prepare(key,1,Stage.DAILY_REPORT,day,null,List.of(),List.of(new Evidence("fictional-input-"+n,"analysis",day,h.now().minusSeconds(1),"a".repeat(64),"{\"fictional\":true}",true,false,"accepted",List.of())),List.of(),true,"a".repeat(64));var store=new GenerationJdbcStore(h.h.f.source,h.h.f.clock,"fictional-owner",id,c);new GenerationFlowService(h.h.f.writes,store,(stage,snapshot,hash)->"{\"provider\":\"fictional\",\"model\":\"fictional\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional\"}],\"response_format\":{},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}",h.h.f.clock,()->true).prepare(c,new SharedWriteExecutor.ActionContext("fictional-daily-prepare-"+n,id,"a".repeat(64)));return true;});}
    @Test void OriginalRunningAndCompletedDailyRootsAreLocatorsWithoutGrantingCompletion(){var page=rr(()->pages.read(DAY,DAY.plusDays(1),null));assertEquals(List.of(ResultJdbcStoreTest.ROOT),page.roots());assertFalse(page.hasMore());assertNull(h.read());h.apply();assertNull(h.read());h.observe(h.now());assertNotNull(h.read());assertEquals(page,rr(()->pages.read(DAY,DAY.plusDays(1),null)));assertFalse(page.toString().contains("fictional"));assertThrows(UnsupportedOperationException.class,()->page.roots().clear());}
    @Test void ActualSeventyRootsKeepTheSixtyFifthLookaheadAndEveryLaterPage(){for(int n=0;n<70;n++)queued(n,DAY,"fictional-report-key-"+n);var all=rr(()->{var ids=new ArrayList<String>();String cursor=null;boolean more;do{var page=pages.read(DAY,DAY.plusDays(1),cursor);assertTrue(page.roots().size()<=64);ids.addAll(page.roots());cursor=page.nextCursor();more=page.hasMore();}while(more);return ids;});assertEquals(71,all.size());assertEquals(71,new HashSet<>(all).size());assertTrue(all.contains(ResultJdbcStoreTest.ROOT));assertEquals(1,h.h.f.sends);assertTrue(rr(()->pages.read(DAY,DAY.plusDays(1),null)).hasMore());}
    @Test void CanonicalOriginalDayKeyStillFindsAHeaderWhoseStageOrDayWasChanged(){queued(100,DAY,"report:daily:"+DAY);String root=JdbcFrozenInputs.stableId("fictional-daily-root","100");h.h.f.jdbc.update("UPDATE jobs SET task_type='agent_analysis',business_date=? WHERE id=?",DAY.minusDays(1).toString(),root);var values=rr(()->pages.read(DAY,DAY.plusDays(1),null));assertTrue(values.roots().contains(root));assertThrows(RuntimeException.class,()->rr(()->new JdbcReportTaskHeaders(h.h.f.source).read(root)));}
    @Test void ExactPeriodBoundsDoNotTurnUnknownDaysIntoEmptyEvidence(){queued(200,DAY.minusDays(1),"fictional-outside-key");assertEquals(List.of(ResultJdbcStoreTest.ROOT),rr(()->pages.read(DAY,DAY.plusDays(1),null)).roots());assertTrue(rr(()->pages.read(DAY.plusDays(1),DAY.plusDays(2),null)).roots().isEmpty());assertThrows(RuntimeException.class,()->rr(()->pages.read(DAY,DAY.plusDays(32),null)));assertThrows(RuntimeException.class,()->rr(()->pages.read(DAY,DAY,null)));}
    @Test void NativeLocatorRequiresReadonlyRrAndColdConstruction(){var source=mock(javax.sql.DataSource.class);new JdbcDailyReportRootPages(source);verifyNoInteractions(source);assertThrows(RuntimeException.class,()->pages.read(DAY,DAY.plusDays(1),null));var tx=new TransactionTemplate(h.h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->tx.execute(s->pages.read(DAY,DAY.plusDays(1),null)));assertThrows(RuntimeException.class,()->h.h.f.writes.execute(h.h.f.action("fictional-daily-pages-writer"),key->null,c->pages.read(DAY,DAY.plusDays(1),null)));}
}
