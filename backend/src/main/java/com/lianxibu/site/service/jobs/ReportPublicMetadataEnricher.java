package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Citation display facts only. The report Source and context builder own selection completeness. */
public final class ReportPublicMetadataEnricher implements GenerationFlowService.InputEnricher {
    private final ArticlePublicEvidenceService articles;
    private final SourceMemberEvidenceService members;
    private final FrozenContentVersionService versions;
    public ReportPublicMetadataEnricher(ArticlePublicEvidenceService articles,SourceMemberEvidenceService members,FrozenContentVersionService versions) {
        this.articles=Objects.requireNonNull(articles);this.members=Objects.requireNonNull(members);this.versions=Objects.requireNonNull(versions);
    }
    @Override public JsonNode fields(String rootId,GenerationFlowService.Prepare command,Instant cutoff,JsonNode snapshot) {
        JdbcFrozenInputs.requireTx();WireValues.id(rootId,false);
        if(command==null||command.stage()==null||!command.stage().report()||command.applicationContext()==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        ReportInputProfile.validated(snapshot);
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        var profile=snapshot.get(ReportInputProfile.FIELD);
        if(!context.referencesComplete()||!context.json().equals(command.applicationContext().json())||context.stage()!=command.stage()||context.generation()!=command.generation()
            ||!context.periodStart().equals(WireValues.text(profile.get("period_start")))||!context.periodEnd().equals(WireValues.text(profile.get("period_end")))
            ||!cutoff.equals(WireValues.utcMicroseconds(snapshot.get("cutoff")))||command.generation()!=WireValues.nonnegativeInteger(snapshot.get("generation"))
            ||!command.stage().name().toLowerCase(Locale.ROOT).equals(WireValues.text(snapshot.get("stage")))
            ||!command.logicalKey().equals(WireValues.text(snapshot.get("logical_key")))||!command.businessDate().equals(WireValues.businessDate(snapshot.get("business_date")))
            ||!command.settingsHash().equals(WireValues.sha256(snapshot.get("settings_hash"))))throw invalid();
        Set<String> expected=context.input(rootId,"report-public-metadata",CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot),0).allowedCitationIds();
        Set<String> actual=new HashSet<>(),memberIds=new TreeSet<>();
        for(var target:context.referenceTargets()) {
            if(!actual.add(AnalysisPublicMetadataEnricher.citationId(target)))throw invalid();
            if("catalog_evidence".equals(WireValues.text(target.get("kind"))))memberIds.add(WireValues.id(target.get("item_id"),false));
        }
        if(!actual.equals(expected))throw invalid();
        Map<String,SourceMemberEvidenceService.Fact> facts=new HashMap<>();var ordered=List.copyOf(memberIds);
        for(int offset=0;offset<ordered.size();offset+=100) {
            Set<String> batch=Set.copyOf(ordered.subList(offset,Math.min(offset+100,ordered.size())));var found=members.read(batch,cutoff);
            if(found==null)throw invalid();Set<String> returned=new HashSet<>();
            for(var fact:found) {
                if(fact==null||!batch.contains(fact.id())||!returned.add(fact.id())||!Set.of("agent","skill","workflow","model").contains(fact.category()))throw invalid();
                facts.put(fact.id(),fact);
            }
            if(!returned.equals(batch))throw invalid();
        }
        var metadata=JsonMapper.shared().createObjectNode().put("schema_version","frozen-public-metadata-v1");metadata.putNull("interpretation");var citations=metadata.putArray("citations");
        for(var target:context.referenceTargets()) {
            String title,url;String kind=WireValues.text(target.get("kind"));
            switch(kind) {
                case "article_revision" -> {
                    String id=WireValues.id(target.get("id"),false);var fact=articles.read(id,cutoff);
                    if(fact==null||!id.equals(fact.revisionId()))throw invalid();title=fact.title();url=fact.sourceUrl();
                }
                case "catalog_evidence" -> {
                    var fact=facts.get(WireValues.id(target.get("item_id"),false));if(fact==null)throw invalid();var payload=CanonicalJson.parse(fact.payloadJson());
                    title=fact.title();url=payload.has("subject")?benchmarkUrl(payload):WireValues.text(payload.get("source_url"));
                }
                case "content_version" -> {
                    var key=ContentVersionKey.fromJson(target);var proof=versions.findAt(key,cutoff).orElseThrow(ReportPublicMetadataEnricher::invalid);
                    if(!key.equals(proof.version())||proof.availableAt()==null||proof.availableAt().isAfter(cutoff))throw invalid();
                    var body=CanonicalJson.parse(proof.bodyJson());if(!key.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw invalid();
                    var decoded=FrozenContentBody.read(key.contentKind(),body);
                    title=decoded instanceof ContentService.ReportBody report?report.title():((ContentService.InterpretationBody)decoded).title();url=null;
                }
                default -> throw invalid();
            }
            var citation=citations.addObject().put("id",AnalysisPublicMetadataEnricher.citationId(target)).put("title",title).put("source_url",url);citation.set("target",target.deepCopy());
        }
        FrozenPublicMetadata.read(metadata,context);var extension=JsonMapper.shared().createObjectNode();extension.set(FrozenPublicMetadata.FIELD,metadata);return extension;
    }
    private static String benchmarkUrl(JsonNode payload) {
        var evidence=payload.get("evidence");if(evidence==null||!evidence.isArray())throw invalid();Set<String> urls=new HashSet<>();
        for(var item:evidence)if("benchmark".equals(WireValues.text(item.get("evidence_type"))))urls.add(WireValues.text(item.get("url")));
        return urls.size()==1?urls.iterator().next():null;
    }
    private static GenerationFlowService.InputRejected invalid(){return new GenerationFlowService.InputRejected();}
}
