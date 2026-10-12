package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure serialized entity facts. Neither native existence, commit visibility nor a report cutoff is proved here. */
public record EntityHeatSnapshot(String entityId,String category,long revision,Long occurrenceCount,String title,String summary,Instant firstRecordedAt,LocalDate firstSeenBusinessDate,boolean legacy) {
    public static final int MAX_TEXT_BYTES=65536,MAX_CANONICAL_BYTES=524288,MAX_PHYSICAL_BYTES=786432;
    private static final Set<String> FIELDS=Set.of("schema_version","entity_id","category","revision","occurrence_count","title","summary","first_recorded_at","first_seen_business_date","legacy_flag");
    public EntityHeatSnapshot {
        WireValues.id(entityId,false);if(!Set.of("news_tech","agent","skill","workflow","model").contains(category)||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||occurrenceCount!=null&&(occurrenceCount<0||occurrenceCount>CanonicalJson.MAX_SAFE_INTEGER))throw invalid();
        if(title!=null){if(title.codePointCount(0,title.length())>512)throw invalid();text(title);}if(summary!=null)text(summary);
        if((long)bytes(title)+bytes(summary)>MAX_TEXT_BYTES||firstRecordedAt!=null&&firstRecordedAt.getNano()%1000!=0)throw invalid();
        if(firstRecordedAt!=null&&firstSeenBusinessDate!=null&&firstSeenBusinessDate.isAfter(firstRecordedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))throw invalid();
    }
    public JsonNode toJson(){var n=JsonMapper.shared().createObjectNode().put("schema_version","entity-heat-snapshot-v1").put("entity_id",entityId).put("category",category).put("revision",revision);if(occurrenceCount==null)n.putNull("occurrence_count");else n.put("occurrence_count",occurrenceCount);n.put("title",title).put("summary",summary);if(firstRecordedAt==null)n.putNull("first_recorded_at");else n.put("first_recorded_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(firstRecordedAt));n.put("first_seen_business_date",firstSeenBusinessDate==null?null:firstSeenBusinessDate.toString()).put("legacy_flag",legacy);return n;}
    public String json(){String value=JdbcFrozenInputs.canonical(toJson());if(bytes(value)>MAX_CANONICAL_BYTES)throw invalid();return value;}
    public String hash(){return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,toJson());}
    public static EntityHeatSnapshot read(String value){if(value==null||bytes(value)>MAX_PHYSICAL_BYTES)throw invalid();var n=CanonicalJson.parse(value);WireValues.object(n,FIELDS,FIELDS);if(!"entity-heat-snapshot-v1".equals(WireValues.text(n.get("schema_version")))||!n.get("legacy_flag").isBoolean())throw invalid();var snapshot=new EntityHeatSnapshot(WireValues.id(n.get("entity_id"),false),WireValues.text(n.get("category")),WireValues.nonnegativeInteger(n.get("revision")),n.get("occurrence_count").isNull()?null:WireValues.nonnegativeInteger(n.get("occurrence_count")),nullable(n.get("title")),nullable(n.get("summary")),n.get("first_recorded_at").isNull()?null:WireValues.utcMicroseconds(n.get("first_recorded_at")),n.get("first_seen_business_date").isNull()?null:WireValues.businessDate(n.get("first_seen_business_date")),n.get("legacy_flag").booleanValue());snapshot.json();return snapshot;}
    /** A count-changing decision captures every touched entity, including both sides of a split. */
    public static List<EntityHeatSnapshot> completeTouched(Collection<EntityHeatSnapshot> snapshots,String oldId,String newId){Objects.requireNonNull(snapshots);var expected=new HashSet<String>();if(oldId!=null)expected.add(WireValues.id(oldId,false));if(newId!=null)expected.add(WireValues.id(newId,false));if(snapshots.size()>2||expected.size()!=snapshots.size())throw invalid();var actual=new HashSet<String>();for(var snapshot:snapshots)if(snapshot==null||!actual.add(snapshot.entityId()))throw invalid();if(!actual.equals(expected))throw invalid();return snapshots.stream().sorted(Comparator.comparing(EntityHeatSnapshot::entityId)).toList();}
    private static String nullable(JsonNode node){return node.isNull()?null:WireValues.text(node);}
    private static int bytes(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    private static void text(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("entity heat snapshot shape unavailable");}
    @Override public String toString(){return "EntityHeatSnapshot[redacted]";}
}
