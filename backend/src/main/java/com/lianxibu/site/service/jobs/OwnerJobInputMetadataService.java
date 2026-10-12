package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobViews;
import com.lianxibu.site.service.content.ReportInputProfile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Reads proven original INPUT metadata in the caller's readonly repeatable-read transaction. */
public final class OwnerJobInputMetadataService {
    public record Metadata(String inputSnapshotId,String contentId,JobsService.Coverage coverage,int inputBytes){
        @Override public String toString(){return "OwnerJobInputMetadata[redacted]";}
    }
    private final JdbcOwnerJobViews views;
    private final JdbcFrozenInputs inputs;
    public OwnerJobInputMetadataService(JdbcOwnerJobViews views,JdbcFrozenInputs inputs){this.views=Objects.requireNonNull(views);this.inputs=Objects.requireNonNull(inputs);}
    public Metadata read(String root){
        try{
            var row=views.job(root);var reference=views.inputReference(root);
            if(!reference.present()){if("ready".equals(row.inputState()))throw invalid();return null;}
            String raw=inputs.read(root,reference.hash());int bytes=raw.getBytes(StandardCharsets.UTF_8).length;if(bytes>4*1024*1024)throw invalid();
            var body=CanonicalJson.parse(raw);
            if(!body.isObject()||!"1".equals(WireValues.text(body.get("schema_version")))||!row.taskType().equals(WireValues.text(body.get("stage")))||reference.generation()!=WireValues.nonnegativeInteger(body.get("generation"))||!Objects.equals(row.cutoff(),WireValues.utcMicroseconds(body.get("cutoff")))||!Objects.equals(row.coverageState(),WireValues.text(body.get("coverage_state")))||row.missingJson()==null||!CanonicalJson.parse(row.missingJson()).equals(body.get("missing")))throw invalid();
            JsonNode missing=body.get("missing");if(missing==null||!missing.isArray()||missing.size()>1000)throw invalid();var seen=new HashSet<String>();for(var item:missing){String value=WireValues.text(item);if(value.isBlank()||value.codePointCount(0,value.length())>256||value.codePoints().anyMatch(Character::isISOControl)||!seen.add(value))throw invalid();}
            String content=null;JsonNode context=body.get(FrozenApplicationContext.FIELD);
            if(context!=null){var application=FrozenApplicationContext.fromJson(context);if(!application.stage().name().equalsIgnoreCase(row.taskType())||application.generation()!=reference.generation())throw invalid();content=application.contentId();}
            JobsService.Coverage coverage;
            if(Set.of("daily_report","weekly_report","monthly_report").contains(row.taskType())){
                ReportInputProfile.validated(body);var original=body.get(ReportInputProfile.FIELD).get("coverage");var missingInputs=new ArrayList<JobsService.MissingInput>();
                for(var item:original.get("missing_inputs"))missingInputs.add(new JobsService.MissingInput(WireValues.text(item.get("kind")),nullableText(item.get("id")),item.get("business_date").isNull()?null:WireValues.businessDate(item.get("business_date")),WireValues.text(item.get("reason"))));
                coverage=new JobsService.Coverage(WireValues.text(original.get("state")),List.copyOf(missingInputs),dates(original.get("missing_dates")),dates(original.get("no_update_dates")));
            }else{
                String state=row.coverageState();if(!Set.of("complete","partial","no_input").contains(state)||state.equals("complete")&&!missing.isEmpty())throw invalid();
                var missingInputs=new ArrayList<JobsService.MissingInput>();var dates=new ArrayList<LocalDate>();
                for(var item:missing){String value=item.textValue();if(value.startsWith("date:")){LocalDate day=LocalDate.parse(value.substring(5));if(!value.equals("date:"+day)||dates.size()>=366)throw invalid();dates.add(day);}else{WireValues.id(value,false);missingInputs.add(new JobsService.MissingInput("dependency",value,null,"missing_at_cutoff"));}}
                coverage=new JobsService.Coverage(state,List.copyOf(missingInputs),List.copyOf(dates),List.of());
            }
            return new Metadata(root,content,coverage,bytes);
        }catch(RuntimeException failed){throw invalid();}
    }
    private static String nullableText(JsonNode value){return value.isNull()?null:WireValues.text(value);}
    private static List<LocalDate> dates(JsonNode value){var result=new ArrayList<LocalDate>();for(var date:value)result.add(WireValues.businessDate(date));return List.copyOf(result);}
    private static IllegalStateException invalid(){return new IllegalStateException("original owner job input metadata unavailable");}
}
