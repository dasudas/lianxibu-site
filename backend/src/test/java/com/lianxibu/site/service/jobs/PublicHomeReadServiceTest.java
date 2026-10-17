package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.learning.LearningService;
import com.lianxibu.site.service.settings.SettingsReadService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class PublicHomeReadServiceTest {
    private static final Instant PUBLISHED = Instant.parse("2026-01-01T00:00:00Z");
    private ContentService contents;
    private LearningService learning;
    private SettingsReadService settings;
    private PublicHomeReadService home;
    private DataSourceTransactionManager homeTx;

    @BeforeEach void setup() {
        contents = mock(ContentService.class);
        learning = mock(LearningService.class);
        settings = mock(SettingsReadService.class);
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:home-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        homeTx = new DataSourceTransactionManager(source);
        home = new PublicHomeReadService(contents, learning, settings, homeTx);
    }

    @Test void eachReadRechecksPublishedReportAndPackage() {
        var report = report("r1", "published", 2);
        var pack = new LearningService.PackageSummary("p1", "Fixture", "", 1, "published");
        when(contents.reports(null, 1, 50)).thenReturn(new ContentService.ContentPage(
                List.of(report), 1, 50, 1));
        when(contents.report("r1")).thenReturn(report, null);
        when(learning.packages(1, 50)).thenReturn(new LearningService.Page<>(List.of(pack), 1, 50, 1));
        when(learning.packageSummary("p1")).thenReturn(pack);
        when(settings.resolvePublishedPackage(null)).thenReturn(Optional.of("p1"), Optional.empty());
        var first = home.home();
        assertEquals(1, first.reports().size());
        assertEquals(1, first.learningPackages().size());
        assertEquals("p1", first.defaultPackageId());
        var second = home.home();
        assertTrue(second.reports().isEmpty());
        assertNull(second.defaultPackageId());
        verify(contents, times(2)).report("r1");
        verify(learning, times(2)).packageSummary("p1");
    }

    @Test void withdrawnOrVersionChangedReportAndDraftPackageAreExcluded() {
        var withdrawn = report("withdrawn", "withdrawn", 2);
        var stale = report("stale", "published", 2);
        when(contents.reports(null, 1, 50)).thenReturn(new ContentService.ContentPage(
                List.of(withdrawn, stale), 1, 50, 2));
        when(contents.report("stale")).thenReturn(report("stale", "published", 3));
        var draft = new LearningService.PackageSummary("draft", "Draft", "", 1, "draft");
        when(learning.packages(1, 50)).thenReturn(new LearningService.Page<>(List.of(draft), 1, 50, 1));
        when(learning.packageSummary("draft")).thenReturn(draft);
        when(settings.resolvePublishedPackage(null)).thenReturn(Optional.of("draft"));
        var snapshot = home.home();
        assertTrue(snapshot.reports().isEmpty());
        assertTrue(snapshot.learningPackages().isEmpty());
        assertNull(snapshot.defaultPackageId());
        verify(contents, never()).report("withdrawn");
    }

    private static ContentService.ContentView report(String id, String state, long revision) {
        return new ContentService.ContentView(id, "report", "Fixture", revision, revision,
                state, PUBLISHED, null, null, null,
                new ContentService.Coverage("complete", List.of(), List.of(), List.of()),
                false, List.of(), false, null, null, null, null);
    }

    private static ContentService.ContentView completeReport(String id){
        return new ContentService.ContentView(id,"report","Fictional report",2,1,"published",PUBLISHED,"fictional-input",1L,PUBLISHED,
            new ContentService.Coverage("complete",List.of(),List.of(),List.of()),false,List.of(),false,null,null,
            new ContentService.ReportBody("Fictional report",List.of(),List.of(),"daily",PUBLISHED,PUBLISHED.plusSeconds(86400),"UTC",null),List.of());
    }
    private void emptyLearning(){when(learning.packages(1,50)).thenReturn(new LearningService.Page<>(List.of(),1,50,0));when(settings.resolvePublishedPackage(null)).thenReturn(Optional.empty());}
    @Test void NativeReportSourceRunsOncePerRequestAndNeverKeepsThePreviousList(){
        emptyLearning();var reports=new java.util.ArrayList<>(List.of(completeReport("fictional-native-report")));var calls=new java.util.concurrent.atomic.AtomicInteger();
        var nativeHome=new PublicHomeReadService(contents,learning,settings,homeTx,()->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());calls.incrementAndGet();return reports;
        });
        var first=nativeHome.home();reports.clear();assertEquals(1,first.reports().size());assertTrue(nativeHome.home().reports().isEmpty());assertEquals(2,calls.get());verifyNoInteractions(contents);
    }
    @Test void MalformedOrUnavailableNativeReportsFailClosedWithoutGenericFallback(){
        var sources=new java.util.ArrayList<PublicHomeReadService.SnapshotReports>();sources.add(()->null);sources.add(()->List.of(report("fictional-bad","withdrawn",2)));sources.add(()->List.of(completeReport("fictional-duplicate"),completeReport("fictional-duplicate")));sources.add(()->java.util.stream.IntStream.range(0,51).mapToObj(i->completeReport("fictional-large-"+i)).toList());sources.add(()->{throw new IllegalStateException("fictional native report unavailable");});
        for(var source:sources){var nativeHome=new PublicHomeReadService(contents,learning,settings,homeTx,source);assertThrows(IllegalStateException.class,nativeHome::home);verifyNoInteractions(contents,learning,settings);}
    }
    @Test void NativeReportsStillResolveCurrentLearningPackagesAndTheDefault(){
        var first=new LearningService.PackageSummary("fictional-first","Fictional first","",1,"published");var selected=new LearningService.PackageSummary("fictional-selected","Fictional selected","",2,"published");
        when(learning.packages(1,50)).thenReturn(new LearningService.Page<>(List.of(first),1,50,1));when(learning.packageSummary(first.id())).thenReturn(first);when(learning.packageSummary(selected.id())).thenReturn(selected);when(settings.resolvePublishedPackage(null)).thenReturn(Optional.of(selected.id()));
        var nativeHome=new PublicHomeReadService(contents,learning,settings,homeTx,()->List.of(completeReport("fictional-native-report")));var result=nativeHome.home();assertEquals(1,result.reports().size());assertEquals(List.of(selected,first),result.learningPackages());assertEquals(selected.id(),result.defaultPackageId());verifyNoInteractions(contents);verify(learning).packageSummary(first.id());verify(learning).packageSummary(selected.id());
    }

}
