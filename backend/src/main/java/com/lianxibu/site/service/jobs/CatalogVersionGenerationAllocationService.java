package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Only a verified native pending analysis intent can aggregate a later original source judgment. */
public final class CatalogVersionGenerationAllocationService {
    public record Allocation(long generation,String intentId){ }
    private final CatalogVersionCommitPermitService permits;
    private final EntityAnalysisStateService entities;
    private final JdbcFrozenSourcePlans owners;
    private final JdbcJobRecords records;
    private final String category;
    public CatalogVersionGenerationAllocationService(DataSource source,CatalogVersionCommitPermitService permits,EntityAnalysisStateService entities,String category){
        Objects.requireNonNull(source);this.permits=Objects.requireNonNull(permits);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        this.entities=Objects.requireNonNull(entities);owners=new JdbcFrozenSourcePlans(source);records=new JdbcJobRecords(source);
    }
    public Allocation allocate(CatalogVersionCommitPermitService.Captured captured,Instant now){
        try{
            var p=permits.verify(captured);if(!category.equals(p.category())||now==null||now.getNano()%1000!=0||now.isBefore(WireValues.utcMicroseconds(p.toJson().get("cutoff"))))throw invalid();
            var root=owners.lockOwner(p.rootId());
            if(!"logical_task".equals(root.kind())||!(category+"_analysis").equals(root.type())||root.key()==null||root.key().isBlank()
                    ||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.key()))||root.generation()!=WireValues.nonnegativeInteger(p.toJson().get("generation")))throw invalid();
            if("queued".equals(captured.entityState())){
                if(captured.entityGeneration()<=root.generation())throw invalid();
                String intent=JdbcFrozenInputs.stableId("intent",root.key()+":"+captured.entityGeneration());var pending=owners.lockOwner(intent);
                if(!"generation_intent".equals(pending.kind())||!root.key().equals(pending.key())||!Arrays.equals(root.keyHash(),pending.keyHash())
                        ||captured.entityGeneration()!=pending.generation()||!(category+"_analysis").equals(pending.type())||!captured.businessDate().equals(pending.businessDate())
                        ||!"waiting_input".equals(pending.state())||owners.hasRoots(intent))throw invalid();
                // ensureIntent rechecks the native natural identity, role, key hash and civil date.
                if(!intent.equals(records.ensureIntent(new JdbcJobRecords.Intent(intent,root.key(),captured.entityGeneration(),category+"_analysis",captured.businessDate()),now)))throw invalid();
                return new Allocation(captured.entityGeneration(),intent);
            }
            var state=entities.reserve(p.entityId(),category,captured.entityRevision(),captured.entityGeneration());
            String intent=JdbcFrozenInputs.stableId("intent",root.key()+":"+state.generation());
            if(!intent.equals(records.ensureIntent(new JdbcJobRecords.Intent(intent,root.key(),state.generation(),category+"_analysis",captured.businessDate()),now)))throw invalid();
            return new Allocation(state.generation(),intent);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("native catalog analysis allocation unavailable");}
}
