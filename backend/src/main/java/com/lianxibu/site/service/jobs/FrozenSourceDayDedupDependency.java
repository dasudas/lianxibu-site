package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small immutable day reference. Pure validation never proves SQL existence or grants execution. */
public final class FrozenSourceDayDedupDependency {
    public static final String FIELD="source_day_dedup_dependency_v1";
    private static final int MAX_BYTES=4096;
    private static final Set<String> FIELDS=Set.of("schema_version","category","business_date","directory_owner_id","bundle_hash","cohort_hash","closure_hash","closed_at","cutoff","source_id","run_id","run_generation","config_revision","config_hash","p1_result_hash","available_at","original_batch_eligible");
    private final String json,hash;private final Instant closedAt,cutoff,availableAt;private final boolean originalBatchEligible;
    private FrozenSourceDayDedupDependency(String json,Instant closedAt,Instant cutoff,Instant availableAt,boolean eligible){
        this.json=json;this.hash=CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,CanonicalJson.parse(json));this.closedAt=closedAt;this.cutoff=cutoff;this.availableAt=availableAt;this.originalBatchEligible=eligible;
    }
    public String json(){return json;}public String hash(){return hash;}public JsonNode toJson(){return CanonicalJson.parse(json);}
    public Instant closedAt(){return closedAt;}public Instant cutoff(){return cutoff;}public Instant availableAt(){return availableAt;}public boolean originalBatchEligible(){return originalBatchEligible;}

    /** Derives eligibility from the complete original cohort and closure, never from a caller boolean. */
    public static FrozenSourceDayDedupDependency create(String directoryOwner,String bundleHash,SourceDedupBarrier.Closure closure,FrozenSourceDedupDependency own,Instant preparationCutoff){
        try {
            Objects.requireNonNull(closure);var dependency=verifiedOwn(own,preparationCutoff);var key=dependency.toJson().get("stage_key");
            if(!"news_tech".equals(closure.cohort().category())||!closure.cohort().day().equals(WireValues.businessDate(key.get("business_date")))||dependency.observedAt().isBefore(closure.cohort().registeredAt()))throw invalid();
            String source=WireValues.id(key.get("source_id"),true),run=WireValues.id(key.get("run_id"),false),result=WireValues.sha256(key.get("result_hash"));
            var member=closure.cohort().members().stream().filter(m->m.sourceId().equals(source)).findFirst().orElseThrow(FrozenSourceDayDedupDependency::invalid);
            var entry=closure.entries().stream().filter(e->e.sourceId().equals(source)).findFirst().orElseThrow(FrozenSourceDayDedupDependency::invalid);
            if(member.inclusion()!=SourceDedupBarrier.Inclusion.ENABLED||member.runGeneration()!=1||!member.runId().equals(run)||WireValues.nonnegativeInteger(key.get("generation"))!=1)throw invalid();
            boolean eligible=entry.decision()==SourceDedupBarrier.Decision.INCLUDED;
            if(eligible){
                var fact=entry.progress();var full=dependency.toJson().get("stage_result");
                var state="source-dedup-stage-result-v2".equals(WireValues.text(full.get("schema_version")))?SourceDedupBarrier.State.PARTIAL:WireValues.nonnegativeInteger(full.get("eligible_count"))==0?SourceDedupBarrier.State.EMPTY_SUCCESS:SourceDedupBarrier.State.COMPLETE;
                if(fact==null||fact.state()!=state||!fact.proofHash().equals(result)||!fact.availableAt().equals(dependency.observedAt()))throw invalid();
            }else if(entry.decision()!=SourceDedupBarrier.Decision.MISSING||!dependency.observedAt().isAfter(closure.cutoff()))throw invalid();
            var value=JsonMapper.shared().createObjectNode().put("schema_version","source-day-dedup-dependency-v1").put("category","news_tech").put("business_date",closure.cohort().day().toString())
                    .put("directory_owner_id",directoryOwner).put("bundle_hash",bundleHash).put("cohort_hash",closure.cohort().hash()).put("closure_hash",closure.hash())
                    .put("closed_at",stamp(closure.observedAt())).put("cutoff",stamp(closure.cutoff())).put("source_id",source).put("run_id",run).put("run_generation",1)
                    .put("config_revision",member.configRevision()).put("config_hash",member.configHash()).put("p1_result_hash",result).put("available_at",stamp(dependency.observedAt())).put("original_batch_eligible",eligible);
            return read(canonical(value),dependency,preparationCutoff);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }

    /** Shape and own P1 binding only. An actual consumer must recheck original day/run/observation SQL. */
    public static FrozenSourceDayDedupDependency read(String raw,FrozenSourceDedupDependency own,Instant preparationCutoff){
        try {
            if(raw==null)throw invalid();if(raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
            var value=CanonicalJson.parse(raw);WireValues.object(value,FIELDS,FIELDS);if(!raw.equals(canonical(value))||!"source-day-dedup-dependency-v1".equals(WireValues.text(value.get("schema_version")))||!"news_tech".equals(WireValues.text(value.get("category"))))throw invalid();
            var dependency=verifiedOwn(own,preparationCutoff);var key=dependency.toJson().get("stage_key");var day=WireValues.businessDate(value.get("business_date"));
            WireValues.id(value.get("directory_owner_id"),false);for(String field:List.of("bundle_hash","cohort_hash","closure_hash","config_hash","p1_result_hash"))WireValues.sha256(value.get(field));
            WireValues.nonnegativeInteger(value.get("config_revision"));if(WireValues.nonnegativeInteger(value.get("run_generation"))!=1||WireValues.nonnegativeInteger(key.get("generation"))!=1)throw invalid();
            if(!value.get("business_date").equals(key.get("business_date"))||!WireValues.id(value.get("source_id"),true).equals(WireValues.id(key.get("source_id"),true))||!WireValues.id(value.get("run_id"),false).equals(WireValues.id(key.get("run_id"),false))||!value.get("p1_result_hash").equals(key.get("result_hash")))throw invalid();
            Instant closed=WireValues.utcMicroseconds(value.get("closed_at")),cutoff=WireValues.utcMicroseconds(value.get("cutoff")),available=WireValues.utcMicroseconds(value.get("available_at"));
            Instant beginning=day.plusDays(1).atTime(0,10).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),deadline=SourceDedupBarrier.deadline(day);
            if(closed.isBefore(beginning)||closed.isAfter(preparationCutoff)||available.isBefore(beginning)||!available.equals(dependency.observedAt())||!cutoff.equals(closed.isBefore(deadline)?closed:deadline)||!value.get("original_batch_eligible").isBoolean())throw invalid();
            boolean eligible=value.get("original_batch_eligible").asBoolean();if(eligible?available.isAfter(cutoff):!available.isAfter(cutoff))throw invalid();
            return new FrozenSourceDayDedupDependency(raw,closed,cutoff,available,eligible);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static FrozenSourceDedupDependency verifiedOwn(FrozenSourceDedupDependency own,Instant cutoff){
        if(own==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();return "source-dedup-dependency-v2".equals(WireValues.text(own.toJson().get("schema_version")))?FrozenSourceDedupDependency.readPartial(own.toJson(),cutoff):FrozenSourceDedupDependency.read(own.toJson(),cutoff);
    }
    private static String canonical(JsonNode value){return new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8);}
    private static String stamp(Instant time){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(time);}
    private static IllegalStateException invalid(){return new IllegalStateException("original source day dedup dependency unavailable");}
    @Override public String toString(){return "FrozenSourceDayDedupDependency[redacted]";}
}
