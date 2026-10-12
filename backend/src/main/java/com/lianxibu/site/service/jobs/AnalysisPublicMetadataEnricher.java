package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds public analysis metadata before hashing, using only the selected proven evidence. */
public final class AnalysisPublicMetadataEnricher implements GenerationFlowService.InputEnricher {
    private static final List<String> METRIC_FIELDS=List.of("benchmark","benchmark_version","task","score","unit","evaluated_at","higher_is_better","evaluation_scope","provenance");
    private final EntityInputEvidenceService entities;
    private final ArticlePublicEvidenceService articles;
    public AnalysisPublicMetadataEnricher(EntityInputEvidenceService entities,ArticlePublicEvidenceService articles){this.entities=Objects.requireNonNull(entities);this.articles=Objects.requireNonNull(articles);}
    /** Shared with the trusted context builder; this is not an arbitrary caller-supplied mapping. */
    public static String citationId(JsonNode target) {
        var key=JsonMapper.shared().createObjectNode().put("purpose","public-citation-v1");key.set("target",target);
        return CanonicalJson.hash(CanonicalJson.Domain.INPUT,key);
    }
    @Override public JsonNode fields(String rootId,GenerationFlowService.Prepare command,Instant cutoff,JsonNode snapshot) {
        WireValues.id(rootId,false);
        if(command==null||!command.stage().analysis()||command.applicationContext()==null||cutoff==null)throw invalid();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!context.referencesComplete()||!context.json().equals(command.applicationContext().json())||context.stage()!=command.stage()||context.generation()!=command.generation())throw invalid();
        var input=context.input(rootId,"public-metadata",CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot),0);
        var members=snapshot.get("members");if(members==null||!members.isArray()||members.isEmpty()||members.size()>100)throw invalid();
        Map<String,JsonNode> selected=new TreeMap<>();for(var member:members)if(selected.putIfAbsent(WireValues.id(member.get("id"),false),member)!=null)throw invalid();
        if(!selected.keySet().equals(input.itemIds()))throw invalid();
        var selection=entities.read(input.targetEntityId(),context.contentKind(),selected.keySet(),cutoff);
        if(selection==null||!input.targetEntityId().equals(selection.entityId())||!context.contentKind().equals(selection.category()))throw invalid();
        Map<String,SourceMemberEvidenceService.Fact> facts=new TreeMap<>();Map<String,JsonNode> payloads=new TreeMap<>();
        for(var fact:selection.members()) {
            if(facts.putIfAbsent(fact.id(),fact)!=null||!selected.containsKey(fact.id())||!context.contentKind().equals(fact.category()))throw invalid();
            var original=CanonicalJson.parse(fact.payloadJson());var member=selected.get(fact.id());var body=member.get("body");
            if(body==null||!Arrays.equals(CanonicalJson.bytes(original),CanonicalJson.bytes(body))||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,original).equals(WireValues.text(member.get("body_hash"))))throw invalid();
            payloads.put(fact.id(),original);
        }
        if(!facts.keySet().equals(selected.keySet()))throw invalid();
        var metadata=JsonMapper.shared().createObjectNode().put("schema_version","frozen-public-metadata-v1");var citations=metadata.putArray("citations");
        Set<String> citationIds=new HashSet<>(),referencedMembers=new HashSet<>();Set<JsonNode> targets=new HashSet<>();
        for(var target:context.referenceTargets()) {
            String id=citationId(target);if(!targets.add(target)||!citationIds.add(id))throw invalid();
            String title,url;String kind=WireValues.text(target.get("kind"));
            if(kind.equals("article_revision")) {
                var fact=articles.read(WireValues.id(target.get("id"),false),cutoff);if(fact==null||!fact.revisionId().equals(WireValues.text(target.get("id"))))throw invalid();title=fact.title();url=fact.sourceUrl();
            } else if("catalog_evidence".equals(kind)) {
                String item=WireValues.id(target.get("item_id"),false);var payload=payloads.get(item);var fact=facts.get(item);
                if(payload==null||fact==null||!referencedMembers.add(item))throw invalid();
                title=fact.title();url=payload.has("subject")?benchmarkUrl(payload):WireValues.text(payload.get("source_url"));
            } else throw invalid();
            var citation=citations.addObject().put("id",id).put("title",title).put("source_url",url);citation.set("target",target.deepCopy());
        }
        if(!citationIds.equals(input.allowedCitationIds())||!referencedMembers.equals(selected.keySet()))throw invalid();
        var profile=metadata.putObject("interpretation");profile.putNull("provider");profile.putArray("tags");profile.putNull("stars");profile.putNull("stars_growth");var metrics=profile.putArray("metrics");
        SourceMemberEvidenceService.Fact representative=null;Instant evidenceTime=null;
        for(var entry:payloads.entrySet()) {
            var payload=entry.getValue();boolean metric=payload.has("subject");var fact=facts.get(entry.getKey());
            if(representative==null||payloads.get(representative.id()).has("subject")&&!metric)representative=fact;
            for(String field:metric?List.of("evaluated_at"):List.of("published_at","updated_at")) {
                var value=payload.get(field);if(value==null)throw invalid();if(!value.isNull()){Instant time=WireValues.utcMicroseconds(value);if(evidenceTime==null||time.isAfter(evidenceTime))evidenceTime=time;}
            }
            if(metric){var summary=metrics.addObject();for(String field:METRIC_FIELDS){var value=payload.get(field);if(value==null)throw invalid();summary.set(field,value.deepCopy());}}
        }
        if(representative==null)throw invalid();profile.put("name",representative.title());profile.put("description",representative.summary());
        if(evidenceTime==null)profile.putNull("evidence_time");else profile.put("evidence_time",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(evidenceTime));
        FrozenPublicMetadata.read(metadata,context);
        var extension=JsonMapper.shared().createObjectNode();extension.set(FrozenPublicMetadata.FIELD,metadata);return extension;
    }
    private static String benchmarkUrl(JsonNode payload) {
        Set<String> urls=new HashSet<>();var evidence=payload.get("evidence");if(evidence==null||!evidence.isArray())throw invalid();
        for(var item:evidence)if("benchmark".equals(WireValues.text(item.get("evidence_type"))))urls.add(WireValues.text(item.get("url")));
        return urls.size()==1?urls.iterator().next():null;
    }
    private static GenerationFlowService.InputRejected invalid(){return new GenerationFlowService.InputRejected();}
}
