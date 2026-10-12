package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.service.jobs.VersionValidityService.Action;
import org.junit.jupiter.api.*;

class ContentValiditySummaryServiceTest {
    VersionValidityLedgerServiceTest f;
    ContentValiditySummaryService service;
    @BeforeEach void setup(){f=new VersionValidityLedgerServiceTest();f.setup();service=new ContentValiditySummaryService(f.versions,f.ledger);}
    @Test void completeClosureRetainsHistoryAfterReversal(){
        var target=ContentVersionKey.fromJson(f.content());
        var initial=service.read(target);assertTrue(initial.effectiveValid());assertFalse(initial.hasValidityHistory());assertEquals(2,initial.targetCount());
        var id=f.append(f.command(f.article(),0,Action.INVALIDATE,null),"fictional-invalidate");
        var invalid=service.read(target);assertFalse(invalid.effectiveValid());assertTrue(invalid.hasValidityHistory());
        f.append(f.command(f.article(),1,Action.REVERSE_INVALIDATION,id),"fictional-reverse");
        var restored=service.read(target);assertTrue(restored.effectiveValid());assertTrue(restored.hasValidityHistory());
    }
    @Test void missingReferenceAndUnprovenOwnerCannotAppearUnaffected(){
        var target=ContentVersionKey.fromJson(f.content());f.fixture.jdbc.update("DELETE FROM article_revisions");
        assertThrows(RuntimeException.class,()->service.read(target));
        assertThrows(RuntimeException.class,()->service.read(new ContentVersionKey(target.contentKind(),target.contentId(),target.revision()+1,target.bodyHash(),target.inputJobId())));
    }
}
