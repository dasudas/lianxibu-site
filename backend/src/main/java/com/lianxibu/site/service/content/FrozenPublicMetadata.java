package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.content.ContentService.*;
import com.lianxibu.site.service.jobs.FrozenApplicationContext;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Closed public values from a proven immutable input; never consults current data. */
public final class FrozenPublicMetadata {
    public static final String FIELD="public_metadata_v1";
    public record Metadata(List<Citation> citations, InterpretationProfile interpretation) {
        public Metadata { citations=List.copyOf(citations); }
    }
    private FrozenPublicMetadata() { }

    public static Metadata read(JsonNode node,FrozenApplicationContext context) {
        try { return decode(node,Objects.requireNonNull(context)); }
        catch(RuntimeException error) { throw new IllegalArgumentException("invalid_frozen_public_metadata"); }
    }
    private static Metadata decode(JsonNode node,FrozenApplicationContext context) {
        closed(node,"schema_version","citations","interpretation");
        if(!"frozen-public-metadata-v1".equals(text(node.get("schema_version"),1,64))
                ||!(context.stage().report()||context.stage().analysis())||!context.referencesComplete())throw invalid();
        Set<JsonNode> targets=new HashSet<>(context.referenceTargets());
        Set<String> expected=new HashSet<>();context.toJson().get("allowed_citation_ids").forEach(n->expected.add(WireValues.id(n,false)));
        Set<String> seen=new HashSet<>();List<Citation> citations=new ArrayList<>();
        for(var entry:array(node.get("citations"),1000)) {
            closed(entry,"id","target","title","source_url");
            String id=WireValues.id(entry.get("id"),false);JsonNode target=entry.get("target");
            if(!seen.add(id)||!expected.contains(id)||!targets.contains(target))throw invalid();
            String kind=WireValues.text(target.get("kind"));
            boolean version="content_version".equals(kind);
            citations.add(new Citation(id,kind,text(entry.get("title"),1,512),url(entry.get("source_url")),
                version?WireValues.nonnegativeInteger(target.get("revision")):null,
                version?WireValues.sha256(target.get("body_hash")):null));
        }
        if(!seen.equals(expected))throw invalid();
        var profile=node.get("interpretation");
        if(context.stage().report()) { if(!profile.isNull())throw invalid();return new Metadata(citations,null); }
        closed(profile,"name","provider","tags","description","evidence_time","metrics","stars","stars_growth");
        List<String> tags=new ArrayList<>();Set<String> unique=new HashSet<>();
        for(var tag:array(profile.get("tags"),100)) { String value=text(tag,1,128);if(!unique.add(value))throw invalid();tags.add(value); }
        List<MetricSummary> metrics=new ArrayList<>();
        for(var metric:array(profile.get("metrics"),100)) {
            closed(metric,"benchmark","benchmark_version","task","score","unit","evaluated_at","higher_is_better","evaluation_scope","provenance");
            String score=nullableText(metric.get("score"),1,30);
            if(score!=null&&!score.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?"))throw invalid();
            if(!metric.get("higher_is_better").isBoolean())throw invalid();
            metrics.add(new MetricSummary(text(metric.get("benchmark"),1,128),text(metric.get("benchmark_version"),1,256),
                text(metric.get("task"),1,256),score,choice(metric.get("unit"),"percent","ratio","points","seconds","count"),
                instant(metric.get("evaluated_at")),metric.get("higher_is_better").booleanValue(),
                choice(metric.get("evaluation_scope"),"entity","configuration"),choice(metric.get("provenance"),"author_self_report","third_party")));
        }
        return new Metadata(citations,new InterpretationProfile(text(profile.get("name"),1,512),nullableText(profile.get("provider"),1,256),
            List.copyOf(tags),nullableText(profile.get("description"),0,16384),instant(profile.get("evidence_time")),List.copyOf(metrics),
            number(profile.get("stars")),number(profile.get("stars_growth"))));
    }
    private static void closed(JsonNode node,String... names) { var fields=Set.of(names);WireValues.object(node,fields,fields); }
    private static JsonNode array(JsonNode node,int max) { if(node==null||!node.isArray()||node.size()>max)throw invalid();return node; }
    private static String text(JsonNode node,int min,int max) {
        String value=WireValues.text(node);int count=value.codePointCount(0,value.length());
        if(count<min||count>max)throw invalid();return value;
    }
    private static String nullableText(JsonNode node,int min,int max) { return node.isNull()?null:text(node,min,max); }
    private static Long number(JsonNode node) { return node.isNull()?null:WireValues.nonnegativeInteger(node); }
    private static Instant instant(JsonNode node) { return node.isNull()?null:WireValues.utcMicroseconds(node); }
    private static String choice(JsonNode node,String... values) { String value=WireValues.text(node);if(!Set.of(values).contains(value))throw invalid();return value; }
    private static String url(JsonNode node) {
        if(node.isNull())return null;String value=text(node,1,2000);URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_frozen_public_metadata"); }
}
