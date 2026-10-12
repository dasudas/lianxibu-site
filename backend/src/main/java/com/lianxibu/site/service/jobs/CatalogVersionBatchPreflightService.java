package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Input;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Outside-writer native batch preflight. Each original permanent version judgment precedes current qualification. */
public final class CatalogVersionBatchPreflightService {
    public static final class Batch {
        private final Input input;private final String snapshot;private final FrozenCatalogVersionProjections frozen;
        private final Map<String,CatalogVersionCommitPermitService.Captured> permissions;private final Map<String,CatalogVersionJudgmentLedger.Stored> committed;
        private Batch(Input input,String snapshot,FrozenCatalogVersionProjections frozen,Map<String,CatalogVersionCommitPermitService.Captured> permissions,Map<String,CatalogVersionJudgmentLedger.Stored> committed){this.input=input;this.snapshot=snapshot;this.frozen=frozen;this.permissions=Map.copyOf(permissions);this.committed=Map.copyOf(committed);}
        public Input input(){return input;}public String snapshot(){return snapshot;}public FrozenCatalogVersionProjections frozen(){return frozen;}public Map<String,CatalogVersionCommitPermitService.Captured> permissions(){return permissions;}public Map<String,CatalogVersionJudgmentLedger.Stored> committed(){return committed;}
        @Override public String toString(){return "CatalogVersionBatchPreflight[redacted]";}
    }
    private record Original(Input input,String snapshot,FrozenCatalogVersionProjections frozen){ }
    private final JdbcCatalogVersionFrozenInputs inputs;private final CatalogVersionJudgmentLedger ledger;private final CatalogVersionCommitPermitService permits;private final TransactionTemplate reads;private final String category;
    public CatalogVersionBatchPreflightService(DataSource source,PlatformTransactionManager manager,String category){
        Objects.requireNonNull(source);Objects.requireNonNull(manager);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};inputs=new JdbcCatalogVersionFrozenInputs(source,category);ledger=new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(source),category);permits=new CatalogVersionCommitPermitService(source,manager,category);reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public CatalogVersionCommitPermitService permits(){return permits;}
    public Batch capture(Input input){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive()||input==null||!"2".equals(input.dedupResultVersion())||!(category+"_dedup").equals(input.stage().name().toLowerCase(Locale.ROOT)))throw invalid();
            var original=Objects.requireNonNull(reads.execute(s->{var stored=inputs.read(input.rootId(),input.inputHash());var node=CanonicalJson.parse(stored.json());var frozenInput=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD)).input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision());if(!frozenInput.equals(input))throw invalid();var frozen=FrozenCatalogVersionProjections.read(node.get(FrozenCatalogVersionProjections.FIELD),frozenInput);for(var entry:frozen.entries().values())if(!entry.cutoff().equals(stored.cutoff()))throw invalid();return new Original(frozenInput,stored.json(),frozen);}));
            var permissions=new HashMap<String,CatalogVersionCommitPermitService.Captured>();var committed=new HashMap<String,CatalogVersionJudgmentLedger.Stored>();
            for(var entry:original.frozen().entries().values().stream().sorted(Comparator.comparing(FrozenCatalogVersionProjections.Entry::itemId)).toList()){
                var previous=reads.execute(s->ledger.read(entry.judgmentKey()));if(previous.isPresent()){var record=previous.orElseThrow();if(!entry.entityId().equals(record.entityId()))throw invalid();committed.put(entry.itemId(),record);continue;}
                var captured=permits.captureFrozen(original.input().rootId(),original.input().inputHash(),entry.itemId());if(!entry.matches(captured.projection()))throw invalid();permissions.put(entry.itemId(),captured);
            }
            return new Batch(original.input(),original.snapshot(),original.frozen(),permissions,committed);
        }catch(JdbcCatalogVersionFrozenInputs.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog version batch preflight unavailable");}
}
