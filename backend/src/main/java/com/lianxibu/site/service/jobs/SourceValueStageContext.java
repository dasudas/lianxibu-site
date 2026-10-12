package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete P2 original dependency and coverage, including plans with no AI fragments. */
public final class SourceValueStageContext {
    private static final int MAX_BYTES=2*1048576+4096;
    private SourceValueStageContext(){ }
    public static String create(FrozenSourceDedupDependency dependency,LocalDate day,Instant cutoff,boolean partial) {
        if(dependency!=null&&"source-dedup-dependency-v2".equals(dependency.toJson().get("schema_version").asText()))return createPartialWithinCapacity(dependency,day,cutoff,partial,PARTIAL_MAX_BYTES);
        var value=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-context-v1");value.set(FrozenSourceDedupDependency.FIELD,dependency.toJson());value.put("coverage_state",partial?"partial":"complete");
        var missing=value.putArray("missing");if(partial)missing.add("collection:"+CanonicalJson.parse(dependency.observationKeyJson()).get("run_id").asText());return read(canonical(value),day,cutoff);
    }
    public static String read(String raw,LocalDate day,Instant cutoff) {
        try {
            if(raw==null||day==null||cutoff==null)throw invalid();if(raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
            var value=CanonicalJson.parse(raw);var fields=Set.of("schema_version",FrozenSourceDedupDependency.FIELD,"coverage_state","missing");WireValues.object(value,fields,fields);
            if(!raw.equals(canonical(value))||!"source-value-stage-context-v1".equals(WireValues.text(value.get("schema_version"))))throw invalid();
            var dependency=FrozenSourceDedupDependency.read(value.get(FrozenSourceDedupDependency.FIELD),cutoff);var key=CanonicalJson.parse(dependency.observationKeyJson());if(!day.equals(WireValues.businessDate(key.get("business_date"))))throw invalid();
            String coverage=WireValues.text(value.get("coverage_state"));var missing=value.get("missing");if(!missing.isArray()||!Set.of("complete","partial").contains(coverage))throw invalid();
            if(coverage.equals("complete")?!missing.isEmpty():missing.size()!=1||!missing.get(0).isTextual()||!missing.get(0).asText().equals("collection:"+WireValues.id(key.get("run_id"),false)))throw invalid();return raw;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static String fromSnapshot(JsonNode snapshot) {
        try {
            if(!"news_value".equals(WireValues.text(snapshot.get("stage"))))throw invalid();var value=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-context-v1");
            for(String field:List.of(FrozenSourceDedupDependency.FIELD,"coverage_state","missing")){if(!snapshot.has(field))throw invalid();value.set(field,snapshot.get(field).deepCopy());}
            return read(canonical(value),WireValues.businessDate(snapshot.get("business_date")),Instant.parse(WireValues.text(snapshot.get("cutoff"))));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static final int PARTIAL_MAX_BYTES=4*1048576;
    public static final int DAY_MAX_BYTES=PARTIAL_MAX_BYTES+8192;
    /** New day context keeps the original full/partial coverage representation unchanged. */
    public static String createDay(FrozenSourceDedupDependency dependency,FrozenSourceDayDedupDependency dayDependency,LocalDate day,Instant cutoff,boolean collectionPartial){
        try {
            var value=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(create(dependency,day,cutoff,collectionPartial));
            value.put("schema_version","source-value-stage-context-v3");value.set(FrozenSourceDayDedupDependency.FIELD,Objects.requireNonNull(dayDependency).toJson());return readDay(canonical(value),day,cutoff);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static String readAny(String raw,LocalDate day,Instant cutoff){
        try {
            if(raw==null)throw invalid();if(raw.getBytes(StandardCharsets.UTF_8).length>DAY_MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
            return switch(WireValues.text(CanonicalJson.parse(raw).get("schema_version"))){case "source-value-stage-context-v1"->read(raw,day,cutoff);case "source-value-stage-context-v2"->readPartial(raw,day,cutoff);case "source-value-stage-context-v3"->readDay(raw,day,cutoff);default->throw invalid();};
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static String readDay(String raw,LocalDate day,Instant cutoff){
        try {
            if(raw==null)throw invalid();if(raw.getBytes(StandardCharsets.UTF_8).length>DAY_MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
            var value=CanonicalJson.parse(raw);var names=Set.of("schema_version",FrozenSourceDedupDependency.FIELD,FrozenSourceDayDedupDependency.FIELD,"coverage_state","missing");WireValues.object(value,names,names);
            if(!raw.equals(canonical(value))||!"source-value-stage-context-v3".equals(WireValues.text(value.get("schema_version"))))throw invalid();
            var base=(tools.jackson.databind.node.ObjectNode)value.deepCopy();base.remove(FrozenSourceDayDedupDependency.FIELD);boolean partial="source-dedup-dependency-v2".equals(WireValues.text(base.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));
            base.put("schema_version",partial?"source-value-stage-context-v2":"source-value-stage-context-v1");if(partial)readPartial(canonical(base),day,cutoff);else read(canonical(base),day,cutoff);
            var own=partial?FrozenSourceDedupDependency.readPartial(base.get(FrozenSourceDedupDependency.FIELD),cutoff):FrozenSourceDedupDependency.read(base.get(FrozenSourceDedupDependency.FIELD),cutoff);
            FrozenSourceDayDedupDependency.read(canonical(value.get(FrozenSourceDayDedupDependency.FIELD)),own,cutoff);return raw;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static String fromDaySnapshot(JsonNode snapshot){
        try {
            boolean partial="source-dedup-dependency-v2".equals(WireValues.text(snapshot.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));
            var value=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(partial?fromPartialSnapshot(snapshot):fromSnapshot(snapshot));value.put("schema_version","source-value-stage-context-v3");
            value.set(FrozenSourceDayDedupDependency.FIELD,Objects.requireNonNull(snapshot.get(FrozenSourceDayDedupDependency.FIELD)).deepCopy());return readDay(canonical(value),WireValues.businessDate(snapshot.get("business_date")),Instant.parse(WireValues.text(snapshot.get("cutoff"))));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private record Omission(String kind,String id,String reason){ }
    static String createPartialWithinCapacity(FrozenSourceDedupDependency dependency,LocalDate day,Instant cutoff,boolean collectionPartial,int maximum){
        try {
            if(maximum<1||maximum>PARTIAL_MAX_BYTES)throw invalid();var actual=FrozenSourceDedupDependency.readPartial(dependency.toJson(),cutoff);
            var value=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-context-v2");value.set(FrozenSourceDedupDependency.FIELD,actual.toJson());value.put("coverage_state","partial");value.set("missing",missing(actual,collectionPartial));String raw=canonical(value);if(raw.getBytes(StandardCharsets.UTF_8).length>maximum)throw new GenerationFlowService.CapacityGuarded();return readPartial(raw,day,cutoff);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static String readPartial(String raw,LocalDate day,Instant cutoff){
        try {
            if(raw==null||day==null||cutoff==null)throw invalid();if(raw.getBytes(StandardCharsets.UTF_8).length>PARTIAL_MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
            var value=CanonicalJson.parse(raw);var fields=Set.of("schema_version",FrozenSourceDedupDependency.FIELD,"coverage_state","missing");WireValues.object(value,fields,fields);
            if(!raw.equals(canonical(value))||!"source-value-stage-context-v2".equals(WireValues.text(value.get("schema_version")))||!"partial".equals(WireValues.text(value.get("coverage_state"))))throw invalid();
            var dependency=FrozenSourceDedupDependency.readPartial(value.get(FrozenSourceDedupDependency.FIELD),cutoff);var key=CanonicalJson.parse(dependency.observationKeyJson());if(!day.equals(WireValues.businessDate(key.get("business_date"))))throw invalid();
            var omissions=value.get("missing");if(!omissions.isArray()||omissions.isEmpty()||omissions.size()>10001)throw invalid();boolean collection=false;
            for(var omission:omissions){var names=Set.of("kind","id","reason");WireValues.object(omission,names,names);String kind=WireValues.text(omission.get("kind")),id=WireValues.id(omission.get("id"),false),reason=WireValues.text(omission.get("reason"));
                if(kind.equals("source_run")){if(collection||!id.equals(WireValues.id(key.get("run_id"),false))||!reason.equals("collection_partial"))throw invalid();collection=true;}
                else if(!kind.equals("source_fragment")||!Set.of("dedup_failed","dedup_cancelled","dedup_discarded","dedup_superseded").contains(reason))throw invalid();
            }
            if(!Arrays.equals(CanonicalJson.bytes(omissions),CanonicalJson.bytes(missing(dependency,collection))))throw invalid();return raw;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static String fromPartialSnapshot(JsonNode snapshot){
        try {
            if(!"news_value".equals(WireValues.text(snapshot.get("stage")))||!"partial".equals(WireValues.text(snapshot.get("coverage_state"))))throw invalid();
            LocalDate day=WireValues.businessDate(snapshot.get("business_date"));Instant cutoff=Instant.parse(WireValues.text(snapshot.get("cutoff")));var dependency=FrozenSourceDedupDependency.readPartial(snapshot.get(FrozenSourceDedupDependency.FIELD),cutoff);var nativeMissing=snapshot.get("missing");if(!nativeMissing.isArray()||nativeMissing.isEmpty()||nativeMissing.size()>10001)throw invalid();
            String collection="collection:"+WireValues.id(dependency.toJson().get("stage_key").get("run_id"),false);boolean collectionPartial=false;for(var item:nativeMissing){if(!item.isTextual())throw invalid();if(item.asText().equals(collection))collectionPartial=true;}
            String raw=createPartialWithinCapacity(dependency,day,cutoff,collectionPartial,PARTIAL_MAX_BYTES);
            if(!Arrays.equals(CanonicalJson.bytes(nativeMissing),CanonicalJson.bytes(JsonMapper.shared().valueToTree(partialSnapshotMissing(raw,day,cutoff)))))throw invalid();return raw;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Native Frozen.missing remains String; each string is exactly derived from the typed context. */
    public static List<String> partialSnapshotMissing(String raw,LocalDate day,Instant cutoff){
        var context=CanonicalJson.parse(readAny(raw,day,cutoff));if(!"source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version"))))throw invalid();var result=new ArrayList<String>();
        for(var row:context.get("missing"))result.add(row.get("kind").asText().equals("source_run")?"collection:"+row.get("id").asText():"source_fragment:"+row.get("id").asText()+":"+row.get("reason").asText());result.sort(String::compareTo);return List.copyOf(result);
    }
    private static JsonNode missing(FrozenSourceDedupDependency dependency,boolean collection){
        var rows=new TreeSet<Omission>(Comparator.comparing(Omission::kind).thenComparing(Omission::id).thenComparing(Omission::reason));var profile=dependency.toJson();
        if(collection)rows.add(new Omission("source_run",WireValues.id(profile.get("stage_key").get("run_id"),false),"collection_partial"));
        for(var resolution:profile.get("stage_result").get("resolutions")){String state=WireValues.text(resolution.get("state"));if(!state.equals("succeeded"))rows.add(new Omission("source_fragment",WireValues.id(resolution.get("root_id"),false),"dedup_"+state));}
        var output=JsonMapper.shared().createArrayNode();for(var row:rows)output.addObject().put("kind",row.kind()).put("id",row.id()).put("reason",row.reason());return output;
    }

    private static String canonical(JsonNode value){return new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8);}
    private static IllegalStateException invalid(){return new IllegalStateException("original source value stage context unavailable");}
}
