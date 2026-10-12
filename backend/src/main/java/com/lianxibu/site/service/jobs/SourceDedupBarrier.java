package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure day-wide closure. These values do not grant access to database or model operations. */
public final class SourceDedupBarrier {
    private static final int MAX_BYTES=1048576, MAX_SOURCES=10000;
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private static final Set<String> CATEGORIES=Set.of("news_tech","agent","skill","workflow","model");
    public enum Inclusion { ENABLED, DISABLED, UNCONFIGURED }
    public enum State {
        COMPLETE, PARTIAL, EMPTY_SUCCESS, FAILED, CANCELLED, RETRY_EXHAUSTED, NEEDS_REVIEW,
        WAITING, UNKNOWN_EXTERNAL, BLOCKED_CONFIGURATION, RESULT_CONFLICT, CANCEL_REQUESTED;
        boolean settled(){return switch(this){case COMPLETE,PARTIAL,EMPTY_SUCCESS,FAILED,CANCELLED,RETRY_EXHAUSTED,NEEDS_REVIEW->true;default->false;};}
        boolean usable(){return this==COMPLETE||this==PARTIAL||this==EMPTY_SUCCESS;}
    }
    public enum Decision { INCLUDED, MISSING, EXCLUDED }
    public record Member(String sourceId,Inclusion inclusion,Long configRevision,String configHash,String runId,Long runGeneration) {
        public Member {
            WireValues.id(sourceId,true);Objects.requireNonNull(inclusion);
            if((configRevision==null)!=(configHash==null)||(runId==null)!=(runGeneration==null))throw invalid();
            if(configRevision!=null){safe(configRevision,false);hash(configHash);}
            if(runId!=null){WireValues.id(runId,false);safe(runGeneration,true);}
            if(inclusion==Inclusion.ENABLED&&(configRevision==null||runId==null)
                    ||inclusion!=Inclusion.ENABLED&&runId!=null
                    ||inclusion==Inclusion.UNCONFIGURED&&configRevision!=null)throw invalid();
        }
        @Override public String toString(){return "DedupBarrierMember[redacted]";}
    }
    /** Actual permanent source/run/configuration and terminal proof must be verified by the provider. */
    public record Progress(String category,LocalDate day,String sourceId,long configRevision,String configHash,
                           String runId,long runGeneration,State state,Instant availableAt,String proofHash) {
        public Progress {
            SourceDedupBarrier.category(category);SourceDedupBarrier.day(day);WireValues.id(sourceId,true);safe(configRevision,false);hash(configHash);
            WireValues.id(runId,false);safe(runGeneration,true);Objects.requireNonNull(state);time(availableAt);hash(proofHash);
        }
        @Override public String toString(){return "DedupBarrierProgress[redacted]";}
    }
    public record Entry(String sourceId,Decision decision,String reason,Progress progress) {
        public Entry {WireValues.id(sourceId,true);Objects.requireNonNull(decision);Objects.requireNonNull(reason);}
        @Override public String toString(){return "DedupBarrierEntry[redacted]";}
    }
    public static final class Cohort {
        private final String category,json,hash;private final LocalDate day;private final Instant registeredAt;private final List<Member> members;
        private Cohort(String category,LocalDate day,Instant registeredAt,List<Member> members,String json){
            this.category=category;this.day=day;this.registeredAt=registeredAt;this.members=List.copyOf(members);this.json=json;hash=digest(json);
        }
        public String category(){return category;}public LocalDate day(){return day;}public Instant registeredAt(){return registeredAt;}
        public List<Member> members(){return members;}public String json(){return json;}public String hash(){return hash;}
        @Override public String toString(){return "DedupBarrierCohort[redacted]";}
    }
    public static final class Closure {
        private final Cohort cohort;private final Instant observedAt,cutoff;private final boolean early;private final List<Entry> entries;private final String json,hash;
        private Closure(Cohort cohort,Instant observedAt,Instant cutoff,boolean early,List<Entry> entries,String json){
            this.cohort=cohort;this.observedAt=observedAt;this.cutoff=cutoff;this.early=early;this.entries=List.copyOf(entries);this.json=json;hash=digest(json);
        }
        public Cohort cohort(){return cohort;}public Instant observedAt(){return observedAt;}public Instant cutoff(){return cutoff;}public boolean early(){return early;}
        public List<Entry> entries(){return entries;}public String json(){return json;}public String hash(){return hash;}
        @Override public String toString(){return "DedupBarrierClosure[redacted]";}
    }
    public static Cohort freeze(String category,LocalDate day,Instant registeredAt,List<Member> members){
        category(category);day(day);time(registeredAt);Objects.requireNonNull(members);
        Instant beginning=day.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        if(registeredAt.isBefore(beginning)||!registeredAt.isBefore(deadline(day)))throw invalid();
        if(members.size()>MAX_SOURCES)throw new GenerationFlowService.CapacityGuarded();
        var sorted=members.stream().map(Objects::requireNonNull).sorted(Comparator.comparing(Member::sourceId)).toList();
        var ids=new HashSet<String>();var runs=new HashSet<String>();
        for(var member:sorted)if(!ids.add(member.sourceId())||member.runId()!=null&&!runs.add(member.runId()))throw invalid();
        var node=JsonMapper.shared().createObjectNode().put("schema_version","source-day-dedup-cohort-v1")
                .put("category",category).put("business_date",day.toString()).put("registered_at",stamp(registeredAt));
        var array=node.putArray("members");for(var member:sorted)array.add(memberJson(member));
        return new Cohort(category,day,registeredAt,sorted,canonical(node));
    }
    public static Cohort readCohort(String json,String expectedHash){
        try {
            var node=parse(json);closed(node,"schema_version","category","business_date","registered_at","members");
            if(!"source-day-dedup-cohort-v1".equals(text(node,"schema_version")))throw invalid();
            var members=array(node.get("members"));var values=new ArrayList<Member>();
            for(var member:members){closed(member,"source_id","inclusion","config_revision","config_hash","run_id","run_generation");
                values.add(new Member(text(member,"source_id"),wireEnum(Inclusion.class,text(member,"inclusion")),nullableNumber(member,"config_revision"),nullableText(member,"config_hash"),nullableText(member,"run_id"),nullableNumber(member,"run_generation")));}
            var value=freeze(text(node,"category"),WireValues.businessDate(node.get("business_date")),WireValues.utcMicroseconds(node.get("registered_at")),values);
            if(!value.json().equals(json)||!value.hash().equals(hash(expectedHash)))throw invalid();return value;
        }catch(GenerationFlowService.CapacityGuarded capacity){throw capacity;}catch(RuntimeException bad){throw invalid();}
    }
    /** Null means this complete cohort is still waiting; an absent cohort is an error. */
    public static Closure close(Cohort cohort,Instant now,List<Progress> progress){
        Objects.requireNonNull(cohort);time(now);Objects.requireNonNull(progress);if(now.isBefore(cohort.registeredAt()))throw invalid();
        if(progress.size()>MAX_SOURCES)throw new GenerationFlowService.CapacityGuarded();
        var members=new HashMap<String,Member>();cohort.members().forEach(member->members.put(member.sourceId(),member));
        var facts=new HashMap<String,Progress>();
        for(var fact:progress){Objects.requireNonNull(fact);var member=members.get(fact.sourceId());
            if(member==null||member.inclusion()!=Inclusion.ENABLED||facts.putIfAbsent(fact.sourceId(),fact)!=null
                    ||!cohort.category().equals(fact.category())||!cohort.day().equals(fact.day())
                    ||member.configRevision()!=fact.configRevision()||!member.configHash().equals(fact.configHash())
                    ||!member.runId().equals(fact.runId())||member.runGeneration()!=fact.runGeneration()
                    ||fact.availableAt().isBefore(cohort.registeredAt())||fact.availableAt().isAfter(now))throw invalid();
        }
        Instant deadline=deadline(cohort.day());boolean early=now.isBefore(deadline);
        if(early&&cohort.members().stream().filter(member->member.inclusion()==Inclusion.ENABLED)
                .anyMatch(member->facts.get(member.sourceId())==null||!facts.get(member.sourceId()).state().settled()))return null;
        Instant cutoff=early?now:deadline;var entries=new ArrayList<Entry>();
        for(var member:cohort.members()){
            var fact=facts.get(member.sourceId());Decision decision;String reason;
            if(member.inclusion()!=Inclusion.ENABLED){decision=Decision.EXCLUDED;reason=wire(member.inclusion());}
            else if(fact==null){decision=Decision.MISSING;reason="not_completed";}
            else if(fact.availableAt().isAfter(cutoff)){decision=Decision.MISSING;reason="late";}
            else if(fact.state().usable()){decision=Decision.INCLUDED;reason=wire(fact.state());}
            else {decision=Decision.MISSING;reason=wire(fact.state());}
            entries.add(new Entry(member.sourceId(),decision,reason,fact));
        }
        var node=JsonMapper.shared().createObjectNode().put("schema_version","source-day-dedup-closure-v1")
                .put("cohort_hash",cohort.hash()).put("observed_at",stamp(now)).put("cutoff",stamp(cutoff)).put("closed_early",early);
        var array=node.putArray("sources");
        for(var entry:entries){var item=array.addObject().put("source_id",entry.sourceId()).put("decision",wire(entry.decision())).put("reason",entry.reason());
            if(entry.progress()==null)item.putNull("progress");else item.set("progress",progressJson(entry.progress()));}
        return new Closure(cohort,now,cutoff,early,entries,canonical(node));
    }
    public static Closure readClosure(String json,String expectedHash,Cohort cohort){
        try {
            var node=parse(json);closed(node,"schema_version","cohort_hash","observed_at","cutoff","closed_early","sources");
            if(!"source-day-dedup-closure-v1".equals(text(node,"schema_version"))||!Objects.requireNonNull(cohort).hash().equals(text(node,"cohort_hash")))throw invalid();
            var values=new ArrayList<Progress>();
            for(var entry:array(node.get("sources"))){closed(entry,"source_id","decision","reason","progress");
                if(!entry.get("progress").isNull())values.add(readProgress(entry.get("progress")));}
            var value=close(cohort,WireValues.utcMicroseconds(node.get("observed_at")),values);
            if(value==null||!value.json().equals(json)||!value.hash().equals(hash(expectedHash)))throw invalid();return value;
        }catch(GenerationFlowService.CapacityGuarded capacity){throw capacity;}catch(RuntimeException bad){throw invalid();}
    }
    public static Instant deadline(LocalDate day){day(day);return day.plusDays(1).atTime(4,30).atZone(SHANGHAI).toInstant();}
    private static ObjectNode memberJson(Member member){
        var node=JsonMapper.shared().createObjectNode().put("source_id",member.sourceId()).put("inclusion",wire(member.inclusion()));
        if(member.configRevision()==null)node.putNull("config_revision");else node.put("config_revision",member.configRevision());
        if(member.configHash()==null)node.putNull("config_hash");else node.put("config_hash",member.configHash());
        if(member.runId()==null)node.putNull("run_id");else node.put("run_id",member.runId());
        if(member.runGeneration()==null)node.putNull("run_generation");else node.put("run_generation",member.runGeneration());return node;
    }
    private static ObjectNode progressJson(Progress fact){return JsonMapper.shared().createObjectNode().put("category",fact.category()).put("business_date",fact.day().toString())
            .put("source_id",fact.sourceId()).put("config_revision",fact.configRevision()).put("config_hash",fact.configHash()).put("run_id",fact.runId())
            .put("run_generation",fact.runGeneration()).put("state",wire(fact.state())).put("available_at",stamp(fact.availableAt())).put("proof_hash",fact.proofHash());}
    private static Progress readProgress(JsonNode node){
        closed(node,"category","business_date","source_id","config_revision","config_hash","run_id","run_generation","state","available_at","proof_hash");
        return new Progress(text(node,"category"),WireValues.businessDate(node.get("business_date")),text(node,"source_id"),WireValues.nonnegativeInteger(node.get("config_revision")),
                text(node,"config_hash"),text(node,"run_id"),WireValues.nonnegativeInteger(node.get("run_generation")),wireEnum(State.class,text(node,"state")),
                WireValues.utcMicroseconds(node.get("available_at")),text(node,"proof_hash"));
    }
    private static JsonNode parse(String json){if(json==null)throw invalid();if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();return CanonicalJson.parse(json);}
    private static JsonNode array(JsonNode node){if(node==null||!node.isArray())throw invalid();if(node.size()>MAX_SOURCES)throw new GenerationFlowService.CapacityGuarded();return node;}
    private static void closed(JsonNode node,String...fields){var keys=Set.of(fields);WireValues.object(node,keys,keys);}
    private static String text(JsonNode node,String field){return WireValues.text(node.get(field));}
    private static String nullableText(JsonNode node,String field){return node.get(field).isNull()?null:text(node,field);}
    private static Long nullableNumber(JsonNode node,String field){return node.get(field).isNull()?null:WireValues.nonnegativeInteger(node.get(field));}
    private static String canonical(JsonNode node){byte[] bytes=CanonicalJson.bytes(node);if(bytes.length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();return new String(bytes,StandardCharsets.UTF_8);}
    private static String digest(String json){return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,CanonicalJson.parse(json));}
    private static String wire(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
    private static <T extends Enum<T>> T wireEnum(Class<T> type,String value){var result=Enum.valueOf(type,value.toUpperCase(Locale.ROOT));if(!wire(result).equals(value))throw invalid();return result;}
    private static String hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();return value;}
    private static void category(String value){if(!CATEGORIES.contains(value))throw invalid();}
    private static void day(LocalDate value){if(value==null||!value.toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();}
    private static void safe(long value,boolean positive){if(value<(positive?1:0)||value>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();}
    private static void time(Instant value){
        if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0||!stamp(value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*"))throw invalid();
        try{safe(Math.addExact(Math.multiplyExact(value.getEpochSecond(),1000000L),value.getNano()/1000L),false);}catch(ArithmeticException bad){throw invalid();}
    }
    private static String stamp(Instant value){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("original day dedup barrier unavailable");}
    private SourceDedupBarrier(){ }
}
