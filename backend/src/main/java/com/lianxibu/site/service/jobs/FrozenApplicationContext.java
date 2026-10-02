package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.ResultApplicationService.Input;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Closed, immutable applicability facts, included inside the input-v1 hash.
 * Runtime attempt/root revisions are supplied separately, never confused with
 * the original content baseline or the frozen candidate/reference universe. */
public final class FrozenApplicationContext {
    public static final String FIELD="application_context_v1";
    private static final Set<String> FIELDS=Set.of("schema_version","stage","generation","target_content","target_entity_id","period_start","period_end","item_ids","candidates_by_item","eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets","references_complete");
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final String json,contentKind,contentId,entity,periodStart,periodEnd;
    private final Long contentRevision;
    private final Stage stage;
    private final long generation;
    private final Set<String> items,high,citations,versions;
    private final Map<String,Set<String>> candidates;
    private final List<String> targets;
    private final boolean complete;
    private FrozenApplicationContext(JsonNode original) {
        WireValues.object(original,FIELDS,FIELDS);var root=(ObjectNode)CanonicalJson.parse(CanonicalJson.bytes(original));
        if(!"frozen-application-context-v1".equals(WireValues.text(root.get("schema_version"))))throw invalid();
        String wireStage=WireValues.text(root.get("stage"));try{stage=Stage.valueOf(wireStage.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw invalid();}
        if(!wireStage.equals(stage.name().toLowerCase(Locale.ROOT)))throw invalid();
        generation=WireValues.nonnegativeInteger(root.get("generation"));if(generation<1)throw invalid();
        var target=root.get("target_content");
        if(stage.report()||stage.analysis()) {
            var fields=Set.of("kind","id","base_revision");WireValues.object(target,fields,fields);
            contentKind=WireValues.id(target.get("kind"),false);contentId=WireValues.id(target.get("id"),false);
            contentRevision=target.get("base_revision").isNull()?null:WireValues.nonnegativeInteger(target.get("base_revision"));
            String expected=stage.report()?"report":stage.name().toLowerCase(Locale.ROOT).replace("_analysis","");
            if(!expected.equals(contentKind))throw invalid();
        }else{if(!target.isNull())throw invalid();contentKind=null;contentId=null;contentRevision=null;}
        entity=optionalId(root.get("target_entity_id"));if(stage.analysis()!=(entity!=null))throw invalid();
        if(stage.report()) {
            Instant first=WireValues.utcMicroseconds(root.get("period_start")),last=WireValues.utcMicroseconds(root.get("period_end"));
            var a=first.atZone(ZONE);var b=last.atZone(ZONE);if(!a.toLocalTime().equals(LocalTime.MIDNIGHT)||!b.toLocalTime().equals(LocalTime.MIDNIGHT)||!first.isBefore(last))throw invalid();
            if(stage==Stage.DAILY_REPORT&&!a.toLocalDate().plusDays(1).equals(b.toLocalDate())||stage==Stage.WEEKLY_REPORT&&(a.getDayOfWeek()!=DayOfWeek.MONDAY||!a.toLocalDate().plusDays(7).equals(b.toLocalDate()))||stage==Stage.MONTHLY_REPORT&&(a.getDayOfMonth()!=1||!a.toLocalDate().plusMonths(1).equals(b.toLocalDate())))throw invalid();
            periodStart=root.get("period_start").asText();periodEnd=root.get("period_end").asText();
        }else{if(!root.get("period_start").isNull()||!root.get("period_end").isNull())throw invalid();periodStart=null;periodEnd=null;}
        items=ids(root,"item_ids",1000);high=ids(root,"eligible_high_value_item_ids",1000);citations=ids(root,"allowed_citation_ids",1000);versions=ids(root,"version_eligible_item_ids",1000);
        if(!items.containsAll(high)||!items.containsAll(versions)||(stage.dedup()||stage==Stage.NEWS_VALUE)&&items.isEmpty())throw invalid();
        var map=new TreeMap<String,Set<String>>(FrozenApplicationContext::utf8);var entries=root.get("candidates_by_item");if(!entries.isArray()||entries.size()>1000)throw invalid();
        for(var entry:entries){var fields=Set.of("item_id","entity_ids");WireValues.object(entry,fields,fields);String id=WireValues.id(entry.get("item_id"),false);Set<String> entities=ids((ObjectNode)entry,"entity_ids",1000);if(!items.contains(id)||map.putIfAbsent(id,entities)!=null)throw invalid();}
        candidates=Map.copyOf(map);var sorted=root.putArray("candidates_by_item");map.forEach((id,entities)->{var entry=sorted.addObject();entry.put("item_id",id);var values=entry.putArray("entity_ids");entities.stream().sorted(FrozenApplicationContext::utf8).forEach(values::add);});
        var refs=root.get("reference_targets");if(!refs.isArray()||refs.size()>1000)throw invalid();var all=new TreeSet<String>(FrozenApplicationContext::utf8);
        for(var ref:refs){reference(ref);if(!all.add(canonical(ref)))throw invalid();}targets=List.copyOf(all);var canonicalRefs=root.putArray("reference_targets");all.forEach(t->canonicalRefs.add(CanonicalJson.parse(t)));
        var flag=root.get("references_complete");if(!flag.isBoolean())throw invalid();complete=flag.booleanValue();json=canonical(root);if(json.getBytes(StandardCharsets.UTF_8).length>262144)throw invalid();
    }
    public static FrozenApplicationContext fromJson(JsonNode node){return new FrozenApplicationContext(node);}
    public String json(){return json;}
    public JsonNode toJson(){return CanonicalJson.parse(json);}
    public Stage stage(){return stage;}
    public long generation(){return generation;}
    public String contentKind(){return contentKind;}
    public String contentId(){return contentId;}
    public Long baseContentRevision(){return contentRevision;}
    public boolean referencesComplete(){return complete;}
    public List<JsonNode> referenceTargets(){return targets.stream().map(CanonicalJson::parse).toList();}
    public Input input(String rootId,String attemptId,String inputHash,long expectedRootRevision){WireValues.id(rootId,false);WireValues.id(attemptId,false);if(inputHash==null||!inputHash.matches("[0-9a-f]{64}"))throw invalid();if(expectedRootRevision<0||expectedRootRevision>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return new Input(rootId,attemptId,stage,generation,inputHash,expectedRootRevision,contentRevision,items,candidates,high,citations,versions,entity,periodStart,periodEnd);}
    private static Set<String> ids(ObjectNode root,String field,int maximum){var values=root.get(field);if(values==null||!values.isArray()||values.size()>maximum)throw invalid();var sorted=new TreeSet<String>(FrozenApplicationContext::utf8);for(var value:values)if(!sorted.add(WireValues.id(value,false)))throw invalid();var canonical=root.putArray(field);sorted.forEach(canonical::add);return Set.copyOf(sorted);}
    private static String optionalId(JsonNode n){return n.isNull()?null:WireValues.id(n,false);}
    private static void reference(JsonNode ref){if(ref==null||!ref.isObject())throw invalid();String kind=WireValues.text(ref.get("kind"));if(kind.equals("content_version")){if(ContentVersionKey.fromJson(ref).revision()<1)throw invalid();}else if(kind.equals("article_revision")||kind.equals("catalog_evidence")){String id=kind.equals("article_revision")?"id":"item_id";var fields=Set.of("kind",id);WireValues.object(ref,fields,fields);WireValues.id(ref.get(id),false);}else throw invalid();}
    private static int utf8(String a,String b){return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static String canonical(JsonNode n){return new String(CanonicalJson.bytes(n),StandardCharsets.UTF_8);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid frozen application context");}
}
