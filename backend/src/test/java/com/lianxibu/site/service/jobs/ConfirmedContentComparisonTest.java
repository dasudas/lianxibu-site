package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService;
import org.junit.jupiter.api.*;

class ConfirmedContentComparisonTest extends ConfirmedContentApplicationTest {
    JobResultComparisonReadService.Comparison compare(){var contents=new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences());var service=new JobResultComparisonReadService(new OwnerJobResultComparisonStore(f.source,f.tx,contents,target->"article_revision".equals(target.path("kind").asText())&&f.jdbc.query("SELECT effective_valid FROM article_revisions WHERE id=?",(r,n)->r.getBoolean(1),target.path("id").asText()).stream().anyMatch(Boolean.TRUE::equals)));return (JobResultComparisonReadService.Comparison)service.read(owner,ResultJdbcStoreTest.ROOT);}
    @Test void ConfirmedExternalResultComparisonOffersExplicitApplicationUnderActualNativeProof(){long count=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);var slot=new JdbcWorkerState(f.source).slot();var view=compare();assertTrue(view.canApply());assertTrue(view.blockingReasons().isEmpty());assertEquals(revision(),view.jobRevision());assertEquals(1,view.currentContentRevision());assertEquals(1,view.currentBodyRevision());assertEquals("Fictional report",view.candidateBody().get("title").asText());assertEquals(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash(),view.resultHash());assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(1,f.sends);}
    @Test void OtherStateAndInvalidReferencesCannotBecomeApplicationPermission(){f.jdbc.update("UPDATE jobs SET application_state='FICTIONAL_OTHER_STATE' WHERE id=?",ResultJdbcStoreTest.ROOT);assertFalse(compare().canApply());assertTrue(compare().blockingReasons().contains("application_state_not_conflict"));f.jdbc.update("UPDATE jobs SET application_state='EXTERNAL_RESULT_CONFIRMED' WHERE id=?",ResultJdbcStoreTest.ROOT);invalidateReference();assertFalse(compare().canApply());assertTrue(compare().blockingReasons().contains("invalid_evidence"));assertEquals(1,f.jdbc.queryForObject("SELECT revision FROM contents",Long.class));}
}
