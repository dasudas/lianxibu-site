package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Shared preparation and persistence check against the actual source-owned handoff proof. */
final class CollectionInputProofs {
    private CollectionInputProofs() { }
    static void validate(Prepare command) {
        if(command.collectionProofs()==null)throw new InputRejected();
        FrozenCollectionManifest.create(command.collectionProofs());
        for(var proof:command.collectionProofs()) {
            var facts=proof.facts();
            if(!command.stage().dedup()||!"production".equals(facts.activationStage())
                    ||!facts.sourceId().equals(command.sourceId())||!facts.businessDate().equals(command.businessDate())
                    ||facts.generation()!=command.generation()
                    ||!CollectionHandoffProofService.stage(facts).equals(command.stage().name().toLowerCase(Locale.ROOT))
                    ||!CollectionHandoffProofService.logicalKey(facts).equals(command.logicalKey()))throw new InputRejected();
        }
    }
    static void verify(Prepare command,CollectionProofSource source,boolean required) {
        JdbcFrozenInputs.requireTx();validate(command);
        if(command.collectionProofs().isEmpty()) {
            if(required)throw new InputRejected();
            return;
        }
        if(source==null)throw new InputRejected();
        var actual=new ArrayList<CollectionHandoffProofService.Proof>();
        for(var declared:command.collectionProofs())actual.add(source.read(declared.facts().runId()));
        if(!Arrays.equals(CanonicalJson.bytes(FrozenCollectionManifest.create(command.collectionProofs())),
                CanonicalJson.bytes(FrozenCollectionManifest.create(actual))))throw new InputRejected();
    }

    static void verifyFrozen(JsonNode snapshot,Stage stage,String logicalKey,long generation,
                             LocalDate businessDate,CollectionProofSource source) {
        JdbcFrozenInputs.requireTx();
        if(!stage.dedup()) {
            if(snapshot.has(FrozenCollectionManifest.FIELD))throw new InputRejected();
            return;
        }
        var declared=FrozenCollectionManifest.read(snapshot.get(FrozenCollectionManifest.FIELD));
        if(declared.isEmpty()||source==null)throw new InputRejected();
        String sourceId=declared.getFirst().facts().sourceId();
        var actual=new ArrayList<CollectionHandoffProofService.Proof>();
        for(var proof:declared) {
            var facts=proof.facts();
            if(!"production".equals(facts.activationStage())||!sourceId.equals(facts.sourceId())
                    ||!businessDate.equals(facts.businessDate())||generation!=facts.generation()
                    ||!stage.name().toLowerCase(Locale.ROOT).equals(CollectionHandoffProofService.stage(facts))
                    ||!logicalKey.equals(CollectionHandoffProofService.logicalKey(facts)))throw new InputRejected();
            actual.add(source.read(facts.runId()));
        }
        if(!Arrays.equals(CanonicalJson.bytes(FrozenCollectionManifest.create(declared)),
                CanonicalJson.bytes(FrozenCollectionManifest.create(actual))))throw new InputRejected();
    }
}
