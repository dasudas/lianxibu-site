package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure P3-P6 assembly from complete trusted facts. Provider composition grants qualification. */
public final class CatalogSourcePlanBuilder {
    public SourcePartitionPlanner.Plan build(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,
            CatalogDedupEligibilityService.Selection selection,CollectionHandoffProofService.Proof collection,
            FrozenGenerationSettings settings,NewsCandidateRecall.CategoryHistory history) {
        return build(original,reference,selection,collection,settings,history,null);
    }
    /** New v2 producer: only actual issued qualifications enter the complete original input hash. */
    public SourcePartitionPlanner.Plan buildWithVersions(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,
            CatalogDedupEligibilityService.Selection selection,CollectionHandoffProofService.Proof collection,
            FrozenGenerationSettings settings,NewsCandidateRecall.CategoryHistory history,
            Map<String,CatalogVersionQualificationProjection.Projection> versions){
        return build(original,reference,selection,collection,settings,history,Map.copyOf(Objects.requireNonNull(versions)));
    }
    private SourcePartitionPlanner.Plan build(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,
            CatalogDedupEligibilityService.Selection selection,CollectionHandoffProofService.Proof collection,
            FrozenGenerationSettings settings,NewsCandidateRecall.CategoryHistory history,
            Map<String,CatalogVersionQualificationProjection.Projection> versions){
        try {
            Objects.requireNonNull(original);Objects.requireNonNull(reference);Objects.requireNonNull(selection);
            Objects.requireNonNull(collection);Objects.requireNonNull(settings);Objects.requireNonNull(history);
            var facts=collection.facts();String key=CollectionHandoffProofService.logicalKey(facts);long generation=facts.generation();
            if(!reference.equals(FrozenSourceUniverse.encode(reference.ownerId(),original).reference())||!reference.ownerId().equals(collection.intentId())
                    ||!original.runId().equals(selection.runId())||!original.sourceId().equals(selection.sourceId())
                    ||!original.runId().equals(facts.runId())||!original.sourceId().equals(facts.sourceId())||!selection.category().equals(facts.category())||!history.category().equals(facts.category())
                    ||!"production".equals(facts.activationStage())||!selection.day().equals(facts.businessDate())||!selection.cutoff().equals(history.facts().cutoff())
                    ||!original.manifestHash().equals(facts.manifestHash())||original.members().size()!=facts.expected()||original.accepted()!=facts.accepted()
                    ||original.excluded()!=facts.excluded()||selection.entries().size()!=original.members().size())throw unavailable();
            FrozenCollectionManifest.create(List.of(collection));
            var entries=new TreeMap<String,CatalogDedupEligibilityService.Entry>();
            for(var entry:selection.entries())if(entry==null||entries.putIfAbsent(entry.itemId(),entry)!=null)throw unavailable();
            var decisions=new ArrayList<SourcePartitionPlanner.Decision>();var eligible=new TreeMap<String,CatalogDedupEligibilityService.Entry>();
            for(var member:original.members()) {
                var entry=entries.remove(member.itemId());
                if(entry==null||"excluded".equals(member.terminalState())!=(entry.reason()==CatalogDedupEligibilityService.Reason.COLLECTOR_EXCLUDED))throw unavailable();
                decisions.add(new SourcePartitionPlanner.Decision(member.itemId(),SourcePartitionPlanner.Reason.valueOf(entry.reason().name())));
                if(entry.reason()==CatalogDedupEligibilityService.Reason.ELIGIBLE){verify(entry,selection);eligible.put(entry.itemId(),entry);}
            }
            if(!entries.isEmpty())throw unavailable();
            var queries=eligible.values().stream().map(e->new NewsCandidateRecall.Query(e.itemId(),e.fact().identityKey(),e.fact().title())).toList();
            var recall=NewsCandidateRecall.select(history,queries);var full=CanonicalJson.parse(recall.json());var recallMembers=new HashMap<String,JsonNode>();
            for(var member:full.get("members"))recallMembers.put(member.path("item_id").asText(),member);
            if(versions!=null)verifyVersions(versions,eligible,selection,history,recall);
            var scope=new SourcePartitionPlanner.Scope(selection.day(),selection.cutoff(),settings);
            return new SourcePartitionPlanner().plan(stage(selection.category()),key,generation,original,reference,decisions,scope,
                (ids,part,root)->snapshot(key,generation,reference,scope,collection,eligible,recall,full,recallMembers,ids,part,root,versions));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private static void verifyVersions(Map<String,CatalogVersionQualificationProjection.Projection> versions,
            Map<String,CatalogDedupEligibilityService.Entry> eligible,CatalogDedupEligibilityService.Selection selection,
            NewsCandidateRecall.CategoryHistory history,NewsCandidateRecall.Selection recall){
        if(versions.size()>MemberUniverse.MAX_MEMBERS)throw new GenerationFlowService.CapacityGuarded();
        var entities=new HashMap<String,NewsCandidateRecall.Entity>();history.facts().entities().forEach(entity->entities.put(entity.id(),entity));long bytes=0;
        for(var entry:versions.entrySet()){
            var p=Objects.requireNonNull(entry.getValue());var fact=eligible.get(entry.getKey());if(fact==null||!entry.getKey().equals(p.memberId())||!selection.category().equals(p.category()))throw unavailable();
            var proof=p.toJson();var current=proof.get("current_proof");var entity=entities.get(p.entityId());
            if(entity==null||!entity.identityKey().equals(fact.fact().identityKey())||entity.revision()!=WireValues.nonnegativeInteger(proof.get("entity_revision"))
                    ||!selection.sourceId().equals(current.get("source_id").asText())||!selection.runId().equals(current.get("run_id").asText())
                    ||fact.fact().revision()!=WireValues.nonnegativeInteger(current.get("member_revision"))
                    ||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(fact.fact().payloadJson())).equals(current.get("body_hash").asText())
                    ||!selection.cutoff().equals(WireValues.utcMicroseconds(proof.get("cutoff")))
                    ||recall.candidates().getOrDefault(p.memberId(),List.of()).stream().noneMatch(candidate->candidate.id().equals(p.entityId())))throw unavailable();
            bytes+=CanonicalJson.bytes(proof).length;if(bytes>32L*1024*1024)throw new GenerationFlowService.CapacityGuarded();
        }
    }
    private static void verify(CatalogDedupEligibilityService.Entry entry,CatalogDedupEligibilityService.Selection selection) {
        var fact=entry.fact();if(!selection.category().equals(fact.category())||fact.revision()<0||fact.revision()>=CanonicalJson.MAX_SAFE_INTEGER
                ||!selection.day().equals(entry.businessDate())||entry.completedAt().isAfter(selection.cutoff()))throw unavailable();
        var payload=CanonicalJson.parse(fact.payloadJson());boolean metric=payload.has("subject");
        ObservationPayloadPolicy.validateStored(metric?"metric":"catalog",payload,selection.sourceId());
        var owner=metric?payload.get("subject"):payload;var identity=owner.get("identity");
        String title=metric?WireValues.text(identity.get("key")):WireValues.text(payload.get("title"));
        String summary=metric||payload.get("summary").isNull()?null:WireValues.text(payload.get("summary"));
        if(!selection.category().equals(WireValues.text(owner.get("category")))||!fact.payloadJson().equals(JdbcFrozenInputs.canonical(payload))
                ||!fact.identityKey().equals(JdbcFrozenInputs.canonical(identity))||!fact.title().equals(title)||!Objects.equals(fact.summary(),summary))throw unavailable();
        var published=metric?null:payload.get("published_at");Instant end=selection.day().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        if(published!=null&&!published.isNull()&&!WireValues.utcMicroseconds(published).isBefore(end))throw unavailable();
    }
    private static GenerationFlowService.Stage stage(String category) {
        return switch(category){case "agent"->GenerationFlowService.Stage.AGENT_DEDUP;case "skill"->GenerationFlowService.Stage.SKILL_DEDUP;
            case "workflow"->GenerationFlowService.Stage.WORKFLOW_DEDUP;case "model"->GenerationFlowService.Stage.MODEL_DEDUP;default->throw unavailable();};
    }
    private static String snapshot(String key,long generation,FrozenSourceUniverse.Reference reference,SourcePartitionPlanner.Scope scope,
            CollectionHandoffProofService.Proof collection,Map<String,CatalogDedupEligibilityService.Entry> eligible,NewsCandidateRecall.Selection recall,
            JsonNode full,Map<String,JsonNode> recallMembers,List<String> ids,long part,String rootId,Map<String,CatalogVersionQualificationProjection.Projection> versions) {
        var node=JsonMapper.shared().createObjectNode();node.put("schema_version","1");node.put("logical_key",key);node.put("generation",generation);
        node.put("stage",stage(collection.facts().category()).name().toLowerCase(Locale.ROOT));if(part!=0)node.put("stage_part",part);node.put("business_date",scope.businessDate().toString());node.put("cutoff",versions==null?scope.cutoff().toString():new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(scope.cutoff()));
        node.put("source_id",collection.facts().sourceId());node.put("settings_hash",scope.settings().semanticHash());node.set(FrozenGenerationSettings.FIELD,scope.settings().toJson());
        boolean partial=Set.of("partial","no_usable_input").contains(collection.facts().deliveryState());node.put("coverage_state",partial?"partial":"complete");
        var missing=node.putArray("missing");if(partial)missing.add("collection:"+collection.facts().runId());
        node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(List.of(),List.of()));node.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(List.of(collection)));
        var context=JsonMapper.shared().createObjectNode();context.put("schema_version","frozen-application-context-v1");context.put("stage",stage(collection.facts().category()).name().toLowerCase(Locale.ROOT));context.put("generation",generation);
        for(String field:List.of("target_content","target_entity_id","period_start","period_end"))context.putNull(field);
        var items=context.putArray("item_ids");var candidates=context.putArray("candidates_by_item");
        for(String field:List.of("eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets"))context.putArray(field);context.put("references_complete",true);
        if(versions!=null){context.put("dedup_result_version","2");context.putArray("catalog_version_qualifications");}
        var qualified=new ArrayList<CatalogVersionQualificationProjection.Projection>();
        var members=node.putArray("members");var bindings=node.putObject(FrozenMemberBindings.FIELD);bindings.put("schema_version","frozen-member-bindings-v1");var mapping=bindings.putArray("members");
        var profile=node.putObject(NewsCandidateRecall.FIELD);for(var field:full.properties())if(!field.getKey().equals("members"))profile.set(field.getKey(),field.getValue().deepCopy());var recalled=profile.putArray("members");
        var micros=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
        for(String id:ids) {
            var entry=eligible.get(id);if(entry==null||!recallMembers.containsKey(id))throw unavailable();var fact=entry.fact();var body=CanonicalJson.parse(fact.payloadJson());
            items.add(id);
            if(versions!=null&&versions.containsKey(id)){
                var p=versions.get(id);qualified.add(p);var q=p.resultConstraint();if(q.versionChanged())context.withArray("version_eligible_item_ids").add(id);
                var version=context.withArray("catalog_version_qualifications").addObject().put("item_id",id).put("entity_id",p.entityId()).put("version_changed",q.versionChanged());
                version.putObject("previous_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.previousProofHash());version.putObject("new_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.newProofHash());
            }
            var candidate=candidates.addObject();candidate.put("item_id",id);var allowed=candidate.putArray("entity_ids");recall.candidates().get(id).forEach(entity->allowed.add(entity.id()));
            recalled.add(recallMembers.get(id).deepCopy());var member=members.addObject();member.put("id",id);member.put("kind","source_member");member.put("business_date",entry.businessDate().toString());
            member.put("completed_at",micros.format(entry.completedAt()));member.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));member.set("body",body);member.putArray("references");
            var binding=mapping.addObject();binding.put("item_id",id);binding.put("expected_revision",fact.revision());binding.put("category",fact.category());binding.put("identity_key",fact.identityKey());binding.put("title",fact.title());
            if(fact.summary()==null)binding.putNull("summary");else binding.put("summary",fact.summary());binding.put("new_entity_id",FrozenMemberBindings.entityId(rootId,id,fact.category(),fact.identityKey()));
        }
        var frozenContext=FrozenApplicationContext.fromJson(context);node.set(FrozenApplicationContext.FIELD,frozenContext.toJson());
        if(versions!=null)node.set(FrozenCatalogVersionProjections.FIELD,FrozenCatalogVersionProjections.create(qualified,frozenContext.input(rootId,"version-input-preparation",CanonicalJson.hash(CanonicalJson.Domain.INPUT,node),0)).toJson());
        var partition=node.putObject(SourcePartitionPlanner.FIELD);
        partition.put("schema_version","source-partition-v1");partition.put("stage_part",part);partition.set("universe_ref",CanonicalJson.parse(reference.json()));return JdbcFrozenInputs.canonical(node);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("complete original news source input unavailable");}
}
