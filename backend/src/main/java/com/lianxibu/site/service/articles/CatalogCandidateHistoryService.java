package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Captures one current shared-writer snapshot; it cannot reconstruct older mutable metadata. */
public final class CatalogCandidateHistoryService {
    private final JdbcNewsCandidateHistory store;private final Clock clock;private final String category;
    public CatalogCandidateHistoryService(javax.sql.DataSource source,String category,Clock clock){
        if(category==null||!Set.of("agent","skill","workflow","model").contains(category))throw unavailable();
        this.category=category;this.store=new JdbcNewsCandidateHistory(Objects.requireNonNull(source),category);this.clock=Objects.requireNonNull(clock);
    }
    public NewsCandidateRecall.CategoryHistory capture() {
        try {
            var rows=store.lockAll();if(rows.size()>NewsCandidateRecall.MAX_ENTITIES)throw new NewsCandidateRecall.CapacityGuarded();
            var captured=clock.instant().truncatedTo(ChronoUnit.MICROS);var facts=new ArrayList<NewsCandidateRecall.Entity>();
            for(var row:rows) {
                if(!category.equals(row.category())||row.identityHash()==null||row.revision()==null||row.legacy()==null
                        ||row.legacy()!=0&&row.legacy()!=1||!Arrays.equals(row.identityHash(),JdbcEntityDedup.identityHash(row.category(),row.identityKey())))throw unavailable();
                var fact=new NewsCandidateRecall.Entity(row.id(),row.identityKey(),row.title(),row.summary(),row.revision(),row.firstRecordedAt(),captured,row.analysisState(),row.legacy()==1);
                if(!row.identityKey().equals(fact.identityKey()))throw unavailable();facts.add(fact);
            }
            return new NewsCandidateRecall.CategoryHistory(category,new NewsCandidateRecall.History(captured,facts));
        }catch(NewsCandidateRecall.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("current category candidate history unavailable");}
}
