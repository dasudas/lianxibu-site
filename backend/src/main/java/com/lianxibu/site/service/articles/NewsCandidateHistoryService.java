package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Captures one current shared-writer snapshot; it cannot reconstruct older mutable metadata. */
public final class NewsCandidateHistoryService implements NewsCandidateRecall.HistorySource {
    private final JdbcNewsCandidateHistory store;private final Clock clock;
    public NewsCandidateHistoryService(JdbcNewsCandidateHistory store,Clock clock){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
    @Override public NewsCandidateRecall.History capture() {
        try {
            var rows=store.lockAll();if(rows.size()>NewsCandidateRecall.MAX_ENTITIES)throw new NewsCandidateRecall.CapacityGuarded();
            var captured=clock.instant().truncatedTo(ChronoUnit.MICROS);var facts=new ArrayList<NewsCandidateRecall.Entity>();
            for(var row:rows) {
                if(!"news_tech".equals(row.category())||row.identityHash()==null||row.revision()==null||row.legacy()==null
                        ||row.legacy()!=0&&row.legacy()!=1||!Arrays.equals(row.identityHash(),JdbcEntityDedup.identityHash(row.category(),row.identityKey())))throw unavailable();
                var fact=new NewsCandidateRecall.Entity(row.id(),row.identityKey(),row.title(),row.summary(),row.revision(),row.firstRecordedAt(),captured,row.analysisState(),row.legacy()==1);
                if(!row.identityKey().equals(fact.identityKey()))throw unavailable();facts.add(fact);
            }
            return new NewsCandidateRecall.History(captured,facts);
        }catch(NewsCandidateRecall.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("current news candidate history unavailable");}
}
