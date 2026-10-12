package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Complete native source version capture, followed by fresh locks before freezing its original plan. */
public final class CatalogSourceVersionPlanPermitService {
    public static final class Captured {
        private final Object issuer;
        private final Map<String,CatalogVersionQualificationProjection.Projection> projections;
        private final List<CatalogVersionCommitPermitService.Captured> permissions;
        private Captured(Object issuer,Map<String,CatalogVersionQualificationProjection.Projection> projections,List<CatalogVersionCommitPermitService.Captured> permissions){this.issuer=issuer;this.projections=Map.copyOf(projections);this.permissions=List.copyOf(permissions);}
        public Map<String,CatalogVersionQualificationProjection.Projection> projections(){return projections;}
        @Override public String toString(){return "CapturedCatalogSourceVersions[redacted]";}
    }
    private final Object issuer=new Object();
    private final CatalogSourceVersionQualificationService qualifications;
    private final CatalogVersionCommitPermitService permits;
    private final TransactionTemplate reads;
    public CatalogSourceVersionPlanPermitService(DataSource source,PlatformTransactionManager tx,String category){
        qualifications=new CatalogSourceVersionQualificationService(Objects.requireNonNull(source),Objects.requireNonNull(tx),category);
        permits=new CatalogVersionCommitPermitService(source,tx,category);reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(CatalogDedupEligibilityService.Selection selection,NewsCandidateRecall.CategoryHistory history){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
            return Objects.requireNonNull(reads.execute(s->{
                var projections=qualifications.captureInSnapshot(selection,history);if(projections.size()>MemberUniverse.MAX_MEMBERS)throw new GenerationFlowService.CapacityGuarded();
                var values=new ArrayList<CatalogVersionCommitPermitService.Captured>();
                for(var entry:new TreeMap<>(projections).entrySet()){
                    var p=entry.getValue();var captured=permits.captureInSnapshot(p.entityId(),p.contentId(),p.memberId(),selection.cutoff()).orElseThrow();
                    if(!p.hash().equals(captured.projection().hash())||!p.json().equals(captured.projection().json()))throw invalid();values.add(captured);
                }
                return new Captured(issuer,projections,values);
            }));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** The caller owns one original short RC writer. Every batch is checked before plan mutations. */
    public void verify(Captured captured){
        try{
            JdbcFrozenInputs.requireTx();if(!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||captured==null||captured.issuer!=issuer)throw invalid();
            for(int offset=0;offset<captured.permissions.size();offset+=100){
                var checked=permits.verifyAll(captured.permissions.subList(offset,Math.min(offset+100,captured.permissions.size())));
                checked.forEach((id,p)->{if(!p.hash().equals(captured.projections.get(id).hash()))throw invalid();});
            }
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("native catalog source version plan permission unavailable");}
}
