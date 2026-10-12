package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Complete original day membership, including planned runs which have not yet been registered. */
public final class SourceDayCohortPlan {
    private static final List<String> CATEGORIES=List.of("news_tech","agent","skill","workflow","model");
    private static final int MAX_BYTES=1048576;
    private final JdbcSourceDayRosters.Frozen directory;private final List<SourceDedupBarrier.Cohort> cohorts;
    private final List<SourceDayRunRegistration.Request> requests;private final List<String> unassigned;private final String json,hash;
    private SourceDayCohortPlan(JdbcSourceDayRosters.Frozen directory,List<SourceDedupBarrier.Cohort> cohorts,List<SourceDayRunRegistration.Request> requests,List<String> unassigned,String json){
        this.directory=directory;this.cohorts=List.copyOf(cohorts);this.requests=List.copyOf(requests);this.unassigned=List.copyOf(unassigned);this.json=json;hash=digest(json);
    }
    public JdbcSourceDayRosters.Frozen directory(){return directory;}public List<SourceDedupBarrier.Cohort> cohorts(){return cohorts;}
    public List<SourceDayRunRegistration.Request> requests(){return requests;}public List<String> unassignedSourceIds(){return unassigned;}
    public String metadataJson(){return json;}public String hash(){return hash;}
    public String bundleJson(){
        var root=JsonMapper.shared().createObjectNode().put("schema_version","source-day-cohort-bundle-v1");root.set("manifest",CanonicalJson.parse(json));
        var array=root.putArray("cohorts");for(var cohort:cohorts)array.add(CanonicalJson.parse(cohort.json()));
        byte[] bytes=CanonicalJson.bytes(root);if(bytes.length>2*MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();return new String(bytes,StandardCharsets.UTF_8);
    }
    public String bundleHash(){return digest(bundleJson());}
    public SourceDedupBarrier.Cohort cohort(String category){return cohorts.stream().filter(c->c.category().equals(category)).findFirst().orElseThrow(SourceDayCohortPlan::invalid);}
    public static SourceDayCohortPlan derive(JdbcSourceDayRosters.Frozen original){
        Objects.requireNonNull(original);var evidence=Objects.requireNonNull(original.evidence());
        if(!JdbcSourceDayRosters.owner(original.day()).equals(original.ownerId())||original.registeredAt().isBefore(evidence.capturedAt()))throw invalid();
        window(original.day(),original.registeredAt());window(original.day(),evidence.capturedAt());
        var members=new LinkedHashMap<String,List<SourceDedupBarrier.Member>>();for(String category:CATEGORIES)members.put(category,new ArrayList<>());
        var requests=new ArrayList<SourceDayRunRegistration.Request>();var unassigned=new ArrayList<String>();String directoryHash=evidence.hash();
        for(var source:evidence.sources()){
            if(source.category()==null){unassigned.add(source.id());continue;}
            SourceDedupBarrier.Inclusion inclusion;Long revision=null,generation=null;String configHash=null,runId=null;
            if("disabled".equals(source.activationStage())){
                inclusion=SourceDedupBarrier.Inclusion.DISABLED;if(source.effectivePresent()){revision=source.configRevision();configHash=source.effectiveConfigHash();}
            }else if("production".equals(source.activationStage())&&source.effectivePresent()&&source.configValidUntil()!=null&&source.configValidUntil().isAfter(original.registeredAt())){
                inclusion=SourceDedupBarrier.Inclusion.ENABLED;revision=source.configRevision();configHash=source.effectiveConfigHash();generation=1L;
                runId=JdbcFrozenInputs.stableId("source-day-run",original.day()+"\n"+source.id());
                requests.add(new SourceDayRunRegistration.Request(original.day(),directoryHash,original.registeredAt(),source,runId));
            }else inclusion=SourceDedupBarrier.Inclusion.UNCONFIGURED;
            var category=members.get(source.category());if(category==null)throw invalid();category.add(new SourceDedupBarrier.Member(source.id(),inclusion,revision,configHash,runId,generation));
        }
        var cohorts=new ArrayList<SourceDedupBarrier.Cohort>();for(var category:CATEGORIES)cohorts.add(SourceDedupBarrier.freeze(category,original.day(),original.registeredAt(),members.get(category)));
        if(cohorts.stream().mapToLong(c->c.members().size()).sum()+unassigned.size()!=evidence.sourceCount())throw invalid();
        var root=JsonMapper.shared().createObjectNode().put("schema_version","source-day-cohort-plan-v1").put("business_date",original.day().toString())
                .put("directory_owner_id",original.ownerId()).put("directory_hash",directoryHash).put("registered_at",stamp(original.registeredAt())).put("source_count",evidence.sourceCount());
        var categories=root.putArray("categories");for(var cohort:cohorts)categories.addObject().put("category",cohort.category()).put("cohort_hash",cohort.hash());
        var unknown=root.putArray("unassigned");for(var source:unassigned)unknown.addObject().put("source_id",source).put("reason","category_unassigned");
        String json=new String(CanonicalJson.bytes(root),StandardCharsets.UTF_8);if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
        return new SourceDayCohortPlan(original,cohorts,requests,unassigned,json);
    }
    public static SourceDayCohortPlan read(String json,String expectedHash,JdbcSourceDayRosters.Frozen original){
        if(json==null||expectedHash==null||!expectedHash.matches("[0-9a-f]{64}"))throw invalid();if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
        var plan=derive(original);if(!plan.metadataJson().equals(json)||!plan.hash().equals(expectedHash))throw invalid();return plan;
    }
    public static SourceDayCohortPlan readBundle(String json,String expectedHash,JdbcSourceDayRosters.Frozen original){
        if(json==null||expectedHash==null||!expectedHash.matches("[0-9a-f]{64}"))throw invalid();if(json.getBytes(StandardCharsets.UTF_8).length>2*MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
        String canonical=new String(CanonicalJson.bytes(CanonicalJson.parse(json)),StandardCharsets.UTF_8);var plan=derive(original);
        if(!canonical.equals(plan.bundleJson())||!expectedHash.equals(plan.bundleHash()))throw invalid();return plan;
    }
    private static String digest(String json){return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,CanonicalJson.parse(json));}
    private static void window(LocalDate day,Instant time){
        if(time==null||time.getNano()%1000!=0||time.isBefore(day.plusDays(1).atTime(0,10).atZone(ZoneId.of("Asia/Shanghai")).toInstant())||!time.isBefore(SourceDedupBarrier.deadline(day)))throw invalid();
    }
    private static String stamp(Instant time){return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(time);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid complete source day cohort plan");}
    @Override public String toString(){return "SourceDayCohortPlan[redacted]";}
}
