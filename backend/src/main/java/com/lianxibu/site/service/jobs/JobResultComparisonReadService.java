package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only comparison. A Store must provide durable associations in one consistent snapshot. */
public final class JobResultComparisonReadService {
    public record Snapshot(String jobId, String recordKind, long jobRevision,
            String contentId, String contentKind, Long contentRevision, Long bodyRevision,
            JsonNode currentBody, String inputSnapshotId, Instant cutoff,
            ContentService.Coverage coverage, ResultApplicationService.Input input,
            ResultApplicationService.Validated retained, ResultApplicationService.Current current,
            String knownUnavailable) {
        public Snapshot(String jobId,String recordKind,long jobRevision,String contentId,String contentKind,
                Long contentRevision,Long bodyRevision,JsonNode currentBody,String inputSnapshotId,Instant cutoff,
                ContentService.Coverage coverage,ResultApplicationService.Input input,
                ResultApplicationService.Validated retained,ResultApplicationService.Current current){
            this(jobId,recordKind,jobRevision,contentId,contentKind,contentRevision,bodyRevision,currentBody,
                    inputSnapshotId,cutoff,coverage,input,retained,current,null);
        }
        @Override public String toString(){return "JobResultComparisonSnapshot[redacted]";}
    }
    public interface Store {
        /** Null means absent. Known legacy null metadata differs from unavailable storage.
         * Prove permanent root/content/input/result associations; never reconstruct from current settings. */
        Snapshot snapshot(String jobId);
    }
    public sealed interface View permits Unavailable, Comparison {
        String jobId(); long jobRevision();
    }
    public record Unavailable(String jobId, long jobRevision, String reason) implements View { }
    public record Comparison(String jobId, long jobRevision, String contentKind, String contentId,
            long currentContentRevision, long currentBodyRevision, JsonNode currentBody,
            JsonNode candidateBody, String resultHash, String inputSnapshotId, long generation,
            Instant cutoff, ContentService.Coverage coverage, boolean canApply,
            List<String> blockingReasons) implements View { }
    public static final class Missing extends RuntimeException { }
    public static final class Denied extends RuntimeException { }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final Store store;
    public JobResultComparisonReadService(Store store) { this.store=Objects.requireNonNull(store); }

    public View read(Actor actor,String jobId) {
        if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
        WireValues.id(jobId,false);
        Snapshot value;
        try { value=store.snapshot(jobId); }
        catch(RuntimeException failure) { throw new IllegalStateException("result comparison storage unavailable",failure); }
        if(value==null||!"logical_task".equals(value.recordKind()))throw new Missing();
        try {
            if(!jobId.equals(value.jobId()))throw new IllegalArgumentException();
            number(value.jobRevision());
            if(value.knownUnavailable()!=null){
                if(!Set.of("unproven_input","unproven_current_version","not_content_result").contains(value.knownUnavailable()))throw new IllegalArgumentException();
                return unavailable(value,value.knownUnavailable());
            }
            if(value.retained()==null)return unavailable(value,"no_retained_result");
            if(value.input()==null)return unavailable(value,"unproven_input");
            var input=value.input();
            if(!jobId.equals(input.rootId()))throw new IllegalArgumentException();
            if(!input.stage().report()&&!input.stage().analysis())return unavailable(value,"not_content_result");
            if(value.contentId()==null||value.currentBody()==null)return unavailable(value,"no_current_content");
            if(value.contentRevision()==null||value.bodyRevision()==null)return unavailable(value,"unproven_current_version");
            if(value.inputSnapshotId()==null||value.cutoff()==null||value.coverage()==null)return unavailable(value,"unproven_input");
            WireValues.id(value.contentId(),false);WireValues.id(value.inputSnapshotId(),false);
            number(value.contentRevision());number(value.bodyRevision());number(input.generation());
            if(input.generation()<1)throw new IllegalArgumentException();
            time(value.cutoff());
            var retained=value.retained();
            var core=JSON.createObjectNode();core.put("schema_version","1");core.put("input_hash",retained.inputHash());
            core.set("payload",CanonicalJson.parse(retained.payloadJson()));core.put("result_hash",retained.resultHash());
            var checked=ResultApplicationService.validate(input,new String(CanonicalJson.bytes(core),StandardCharsets.UTF_8));
            if(!checked.equals(retained))throw new IllegalArgumentException();
            JsonNode candidate=copy(CanonicalJson.parse(checked.payloadJson()).get("body"));
            JsonNode current=copy(value.currentBody());
            body(current,value.contentKind());body(candidate,value.contentKind());
            if("report".equals(value.contentKind())) {
                for(String field:List.of("type","period_start","period_end","timezone"))
                    if(!current.get(field).equals(candidate.get(field)))throw new IllegalArgumentException();
            } else if(!current.get("entity_id").equals(candidate.get("entity_id")))throw new IllegalArgumentException();
            var state=value.current();
            if(state==null||state.rootRevision()!=value.jobRevision()
                    ||!Objects.equals(state.contentRevision(),value.contentRevision())
                    ||!Objects.equals(state.retainedResultHash(),retained.resultHash()))throw new IllegalArgumentException();
            number(state.generation());
            if(state.generation()==0||state.inputHash()==null)return unavailable(value,"unproven_input");
            if(!state.inputHash().matches("[0-9a-f]{64}")
                    ||state.appliedResultHash()!=null&&!state.appliedResultHash().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            var blocked=new ArrayList<String>();
            if(state.appliedResultHash()!=null)blocked.add("already_applied");
            if(state.discarded())blocked.add("discarded");
            if(state.generation()!=input.generation()||!Objects.equals(state.inputHash(),input.inputHash()))blocked.add("superseded");
            if(!state.dependenciesValid())blocked.add("invalid_evidence");
            if(!Set.of("CONTENT_REVISION_CONFLICT","EXTERNAL_RESULT_CONFIRMED").contains(state.applicationState()==null?"":state.applicationState()))blocked.add("application_state_not_conflict");
            return new Comparison(jobId,value.jobRevision(),value.contentKind(),value.contentId(),
                    value.contentRevision(),value.bodyRevision(),current,candidate,retained.resultHash(),
                    value.inputSnapshotId(),input.generation(),value.cutoff(),coverage(value.coverage()),
                    blocked.isEmpty(),List.copyOf(blocked));
        } catch(RuntimeException failure) {
            throw new IllegalStateException("result comparison proof unavailable",failure);
        }
    }
    private static Unavailable unavailable(Snapshot v,String reason){return new Unavailable(v.jobId(),v.jobRevision(),reason);}
    private static JsonNode copy(JsonNode value){if(value==null)throw new IllegalArgumentException();return CanonicalJson.parse(new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8));}
    private static void number(long value){if(value<0||value>CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalArgumentException();}
    private static void text(String value,int min,int max){
        if(value==null||value.codePointCount(0,value.length())<min||value.codePointCount(0,value.length())>max)throw new IllegalArgumentException();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new IllegalArgumentException();}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();}
    }
    private static void text(JsonNode value,int min,int max){text(WireValues.text(value),min,max);}
    private static void time(Instant value){if(value==null||value.getNano()%1000!=0||!MICROS.format(value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z"))throw new IllegalArgumentException();}
    private static void ids(JsonNode array){if(array==null||!array.isArray()||array.size()>1000)throw new IllegalArgumentException();for(JsonNode id:array)WireValues.id(id,false);}
    private static void body(JsonNode value,String kind){
        var common=Set.of("title","sections","citation_ids");
        var keys=new java.util.HashSet<>(common);
        if("report".equals(kind))keys.addAll(Set.of("type","period_start","period_end","timezone","learning_task"));
        else if(Set.of("agent","skill","workflow","model").contains(kind))keys.addAll(Set.of("category","entity_id"));
        else throw new IllegalArgumentException();
        WireValues.object(value,keys,keys);text(value.get("title"),1,512);ids(value.get("citation_ids"));
        var sections=value.get("sections");if(!sections.isArray()||sections.size()>100)throw new IllegalArgumentException();
        for(JsonNode section:sections){var fields=Set.of("heading","text","citation_ids");WireValues.object(section,fields,fields);text(section.get("heading"),1,512);text(section.get("text"),0,200000);ids(section.get("citation_ids"));}
        if("report".equals(kind)) {
            String type=WireValues.text(value.get("type"));if(!Set.of("daily","weekly","monthly").contains(type)||!"Asia/Shanghai".equals(WireValues.text(value.get("timezone"))))throw new IllegalArgumentException();
            var start=WireValues.utcMicroseconds(value.get("period_start"));var end=WireValues.utcMicroseconds(value.get("period_end"));if(!start.isBefore(end))throw new IllegalArgumentException();
            var task=value.get("learning_task");if(!task.isNull()){if(!type.equals("daily"))throw new IllegalArgumentException();var fields=Set.of("package_id","object_kind","object_id","title","reason");WireValues.object(task,fields,fields);WireValues.id(task.get("package_id"),false);WireValues.id(task.get("object_id"),false);if(!Set.of("node","question","project").contains(WireValues.text(task.get("object_kind"))))throw new IllegalArgumentException();text(task.get("title"),1,512);text(task.get("reason"),1,2048);}
        } else {if(!kind.equals(WireValues.text(value.get("category"))))throw new IllegalArgumentException();WireValues.id(value.get("entity_id"),false);}
        if(CanonicalJson.bytes(value).length>262144)throw new IllegalArgumentException();
    }
    private static ContentService.Coverage coverage(ContentService.Coverage value){
        if(!Set.of("complete","partial","no_input").contains(value.state())||value.missingInputs()==null||value.missingDates()==null||value.noUpdateDates()==null||value.missingInputs().size()>1000||value.missingDates().size()>366||value.noUpdateDates().size()>366)throw new IllegalArgumentException();
        for(var missing:value.missingInputs()){text(missing.kind(),1,32);text(missing.reason(),1,256);if(missing.id()!=null)WireValues.id(missing.id(),false);if(missing.businessDate()!=null&&!missing.businessDate().toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();}
        for(var dates:List.of(value.missingDates(),value.noUpdateDates()))for(var date:dates)if(date==null||!date.toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();
        return new ContentService.Coverage(value.state(),List.copyOf(value.missingInputs()),List.copyOf(value.missingDates()),List.copyOf(value.noUpdateDates()));
    }
}
