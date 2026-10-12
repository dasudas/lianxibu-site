package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads the complete original archive before authorizing one exact original chunk. */
public final class SourcePartitionProofService {
    private final FrozenSourceStagePlanService plans;
    public SourcePartitionProofService(FrozenSourceStagePlanService plans){this.plans=Objects.requireNonNull(plans);}
    public SourcePartitionPlanner.Chunk require(String rootId,String snapshotJson,String inputHash) {
        return require(rootId,snapshotJson,inputHash,true);
    }
    /** Original immutable archive for preflight; this is not a writer capability. */
    public SourcePartitionPlanner.Chunk requireObserved(String rootId,String snapshotJson,String inputHash){return require(rootId,snapshotJson,inputHash,false);}
    private SourcePartitionPlanner.Chunk require(String rootId,String snapshotJson,String inputHash,boolean writer){
        try {
            if(writer)JdbcFrozenInputs.requireTx();else com.lianxibu.site.repository.jobs.JdbcSourceDedupResults.requireReadOnly();WireValues.id(rootId,false);
            if(snapshotJson==null||snapshotJson.getBytes(StandardCharsets.UTF_8).length>1048576||inputHash==null||!inputHash.matches("[0-9a-f]{64}"))throw unavailable();
            var snapshot=CanonicalJson.parse(snapshotJson);
            if(!inputHash.equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw unavailable();
            var partition=snapshot.get(SourcePartitionPlanner.FIELD);var fields=Set.of("schema_version","stage_part","universe_ref");WireValues.object(partition,fields,fields);
            if(!"source-partition-v1".equals(WireValues.text(partition.get("schema_version"))))throw unavailable();
            var universe=FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(partition.get("universe_ref")));
            long number=WireValues.nonnegativeInteger(partition.get("stage_part"));if(number>=10000)throw unavailable();
            var plan=writer?plans.read(universe.ownerId()):plans.readImmutable(universe.ownerId());
            if(!universe.equals(plan.reference())||!plan.logicalKey().equals(WireValues.text(snapshot.get("logical_key")))
                    ||plan.generation()!=WireValues.nonnegativeInteger(snapshot.get("generation"))
                    ||!plan.stage().name().toLowerCase(Locale.ROOT).equals(WireValues.text(snapshot.get("stage")))||number>=plan.chunks().size())throw unavailable();
            var chunk=plan.chunks().get((int)number);
            if(chunk.stagePart()!=number||!rootId.equals(chunk.rootId())||!inputHash.equals(chunk.inputHash())
                    ||!chunk.snapshotJson().equals(JdbcFrozenInputs.canonical(snapshot)))throw unavailable();return chunk;
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("source partition original plan unavailable");}
}
