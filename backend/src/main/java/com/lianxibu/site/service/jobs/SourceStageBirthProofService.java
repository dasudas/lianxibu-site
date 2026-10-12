package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.util.*;

/** Grants birth only from two complete original archives and the exact physical root. */
public final class SourceStageBirthProofService {
    private final JdbcSourceStageBirth roots;private final JdbcFrozenSourcePlans receipts;private final FrozenSourceStagePlanService archives;
    public SourceStageBirthProofService(JdbcSourceStageBirth roots,JdbcFrozenSourcePlans receipts,FrozenSourceStagePlanService archives){
        this.roots=Objects.requireNonNull(roots);this.receipts=Objects.requireNonNull(receipts);this.archives=Objects.requireNonNull(archives);
    }
    public SourceStageBirthProof read(String rootId){
        try {
            WireValues.id(rootId,false);var root=roots.root(rootId);
            if(root==null||!rootId.equals(root.id())||root.key()==null||root.key().isBlank()||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.key()))
                    ||root.generation()==null||root.generation()<2||root.generation()>CanonicalJson.MAX_SAFE_INTEGER||root.part()==null||root.part()<0||root.part()>=10000
                    ||root.type()==null||root.maximum()==null||root.maximum()!=3||root.calls()==null||root.calls()<0||root.calls()>3)throw unavailable();
            Stage stage=Stage.valueOf(root.type().toUpperCase(Locale.ROOT));
            String owner=JdbcFrozenInputs.stableId("intent",root.key()+":"+root.generation());
            if(!root.type().equals(stage.name().toLowerCase(Locale.ROOT))||!(stage.dedup()||stage==Stage.NEWS_VALUE)||!owner.equals(root.intent())
                    ||!SourcePartitionPlanner.rootId(root.key(),root.generation(),root.part()).equals(rootId)
                    ||roots.previousRootExists(root.keyHash(),root.generation()-1,root.part()))throw unavailable();
            var currentRef=reference(owner);var previousRef=reference(JdbcFrozenInputs.stableId("intent",root.key()+":"+(root.generation()-1)));
            var current=archives.readImmutable(currentRef);var previous=archives.readImmutable(previousRef);
            var proof=SourceStageBirthProof.create(rootId,current,currentRef,previous,previousRef);
            if(!root.key().equals(proof.logicalKey())||root.generation()!=proof.generation()||root.part()!=proof.stagePart()||stage!=proof.stage())throw unavailable();
            return proof;
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private FrozenSourceStagePlan.Reference reference(String owner){
        var receipt=receipts.immutableReceipt(owner);if(receipt==null)throw unavailable();
        return FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(receipt.payloadJson()).get("plan_ref")));
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("actual source stage birth proof unavailable");}
}
