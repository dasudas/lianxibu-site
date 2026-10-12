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

/** Pure P1 assembly from complete trusted facts. Provider composition grants qualification. */
public final class NewsSourcePlanBuilder {
    public SourcePartitionPlanner.Plan build(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,
            NewsDedupEligibilityService.Selection selection,CollectionHandoffProofService.Proof collection,
            FrozenGenerationSettings settings,NewsCandidateRecall.History history) {
        try {
            Objects.requireNonNull(original);Objects.requireNonNull(reference);Objects.requireNonNull(selection);
            Objects.requireNonNull(collection);Objects.requireNonNull(settings);Objects.requireNonNull(history);
            var facts=collection.facts();String key=CollectionHandoffProofService.logicalKey(facts);long generation=facts.generation();
            if(!reference.equals(FrozenSourceUniverse.encode(reference.ownerId(),original).reference())||!reference.ownerId().equals(collection.intentId())
                    ||!original.runId().equals(selection.runId())||!original.sourceId().equals(selection.sourceId())
                    ||!original.runId().equals(facts.runId())||!original.sourceId().equals(facts.sourceId())||!"news_tech".equals(facts.category())
                    ||!"production".equals(facts.activationStage())||!selection.day().equals(facts.businessDate())||!selection.cutoff().equals(history.cutoff())
                    ||!original.manifestHash().equals(facts.manifestHash())||original.members().size()!=facts.expected()||original.accepted()!=facts.accepted()
                    ||original.excluded()!=facts.excluded()||selection.entries().size()!=original.members().size())throw unavailable();
            FrozenCollectionManifest.create(List.of(collection));
            var entries=new TreeMap<String,NewsDedupEligibilityService.Entry>();
            for(var entry:selection.entries())if(entry==null||entries.putIfAbsent(entry.itemId(),entry)!=null)throw unavailable();
            var decisions=new ArrayList<SourcePartitionPlanner.Decision>();var eligible=new TreeMap<String,NewsDedupEligibilityService.Entry>();
            for(var member:original.members()) {
                var entry=entries.remove(member.itemId());
                if(entry==null||"excluded".equals(member.terminalState())!=(entry.reason()==NewsDedupEligibilityService.Reason.COLLECTOR_EXCLUDED))throw unavailable();
                decisions.add(new SourcePartitionPlanner.Decision(member.itemId(),SourcePartitionPlanner.Reason.valueOf(entry.reason().name())));
                if(entry.reason()==NewsDedupEligibilityService.Reason.ELIGIBLE){verify(entry,selection);eligible.put(entry.itemId(),entry);}
            }
            if(!entries.isEmpty())throw unavailable();
            var queries=eligible.values().stream().map(e->new NewsCandidateRecall.Query(e.itemId(),e.fact().identityKey(),e.fact().title())).toList();
            var recall=NewsCandidateRecall.select(history,queries);var full=CanonicalJson.parse(recall.json());var recallMembers=new HashMap<String,JsonNode>();
            for(var member:full.get("members"))recallMembers.put(member.path("item_id").asText(),member);
            var scope=new SourcePartitionPlanner.Scope(selection.day(),selection.cutoff(),settings);
            return new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_DEDUP,key,generation,original,reference,decisions,scope,
                (ids,part,root)->snapshot(key,generation,reference,scope,collection,eligible,recall,full,recallMembers,ids,part,root));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private static void verify(NewsDedupEligibilityService.Entry entry,NewsDedupEligibilityService.Selection selection) {
        var fact=entry.fact();if(!"news_tech".equals(fact.category())||fact.revision()<0||fact.revision()>=CanonicalJson.MAX_SAFE_INTEGER
                ||!selection.day().equals(entry.businessDate())||entry.completedAt().isAfter(selection.cutoff()))throw unavailable();
        var payload=CanonicalJson.parse(fact.payloadJson());ObservationPayloadPolicy.validateStored("article",payload,selection.sourceId());
        var identity=JsonMapper.shared().createObjectNode();identity.put("namespace","article_url");identity.set("key",payload.get("source_url"));
        String summary=payload.get("source_summary").isNull()?null:WireValues.text(payload.get("source_summary"));
        if(!fact.payloadJson().equals(JdbcFrozenInputs.canonical(payload))||!fact.identityKey().equals(JdbcFrozenInputs.canonical(identity))
                ||!fact.title().equals(WireValues.text(payload.get("title")))||!Objects.equals(fact.summary(),summary))throw unavailable();
        String basis=WireValues.text(payload.get("time_basis"));var time=switch(basis){case "published","updated","submitted"->payload.get(basis+"_at");default->null;};
        if(time==null||time.isNull())throw unavailable();Instant observed=WireValues.utcMicroseconds(time);
        Instant first=selection.day().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),last=selection.day().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        if(observed.isBefore(first)||!observed.isBefore(last))throw unavailable();
    }
    private static String snapshot(String key,long generation,FrozenSourceUniverse.Reference reference,SourcePartitionPlanner.Scope scope,
            CollectionHandoffProofService.Proof collection,Map<String,NewsDedupEligibilityService.Entry> eligible,NewsCandidateRecall.Selection recall,
            JsonNode full,Map<String,JsonNode> recallMembers,List<String> ids,long part,String rootId) {
        var node=JsonMapper.shared().createObjectNode();node.put("schema_version","1");node.put("logical_key",key);node.put("generation",generation);
        node.put("stage","news_dedup");if(part!=0)node.put("stage_part",part);node.put("business_date",scope.businessDate().toString());node.put("cutoff",scope.cutoff().toString());
        node.put("source_id",collection.facts().sourceId());node.put("settings_hash",scope.settings().semanticHash());node.set(FrozenGenerationSettings.FIELD,scope.settings().toJson());
        boolean partial=Set.of("partial","no_usable_input").contains(collection.facts().deliveryState());node.put("coverage_state",partial?"partial":"complete");
        var missing=node.putArray("missing");if(partial)missing.add("collection:"+collection.facts().runId());
        node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(List.of(),List.of()));node.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(List.of(collection)));
        var context=JsonMapper.shared().createObjectNode();context.put("schema_version","frozen-application-context-v1");context.put("stage","news_dedup");context.put("generation",generation);
        for(String field:List.of("target_content","target_entity_id","period_start","period_end"))context.putNull(field);
        var items=context.putArray("item_ids");var candidates=context.putArray("candidates_by_item");
        for(String field:List.of("eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets"))context.putArray(field);context.put("references_complete",true);
        var members=node.putArray("members");var bindings=node.putObject(FrozenMemberBindings.FIELD);bindings.put("schema_version","frozen-member-bindings-v1");var mapping=bindings.putArray("members");
        var profile=node.putObject(NewsCandidateRecall.FIELD);for(var field:full.properties())if(!field.getKey().equals("members"))profile.set(field.getKey(),field.getValue().deepCopy());var recalled=profile.putArray("members");
        var micros=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
        for(String id:ids) {
            var entry=eligible.get(id);if(entry==null||!recallMembers.containsKey(id))throw unavailable();var fact=entry.fact();var body=CanonicalJson.parse(fact.payloadJson());
            items.add(id);var candidate=candidates.addObject();candidate.put("item_id",id);var allowed=candidate.putArray("entity_ids");recall.candidates().get(id).forEach(entity->allowed.add(entity.id()));
            recalled.add(recallMembers.get(id).deepCopy());var member=members.addObject();member.put("id",id);member.put("kind","source_member");member.put("business_date",entry.businessDate().toString());
            member.put("completed_at",micros.format(entry.completedAt()));member.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));member.set("body",body);member.putArray("references");
            var binding=mapping.addObject();binding.put("item_id",id);binding.put("expected_revision",fact.revision());binding.put("category",fact.category());binding.put("identity_key",fact.identityKey());binding.put("title",fact.title());
            if(fact.summary()==null)binding.putNull("summary");else binding.put("summary",fact.summary());binding.put("new_entity_id",FrozenMemberBindings.entityId(rootId,id,fact.category(),fact.identityKey()));
        }
        node.set(FrozenApplicationContext.FIELD,FrozenApplicationContext.fromJson(context).toJson());var partition=node.putObject(SourcePartitionPlanner.FIELD);
        partition.put("schema_version","source-partition-v1");partition.put("stage_part",part);partition.set("universe_ref",CanonicalJson.parse(reference.json()));return JdbcFrozenInputs.canonical(node);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("complete original news source input unavailable");}
}
