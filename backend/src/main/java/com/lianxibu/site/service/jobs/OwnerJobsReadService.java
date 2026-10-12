package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobViews;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobViews.Row;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Owner metadata reads only. No read view constitutes worker or application permission. */
public final class OwnerJobsReadService {
    private final JdbcOwnerJobViews views;
    private final OwnerJobInputMetadataService inputs;
    private final TransactionTemplate reads;
    public OwnerJobsReadService(JdbcOwnerJobViews views,OwnerJobInputMetadataService inputs,PlatformTransactionManager tx){
        this.views=Objects.requireNonNull(views);this.inputs=Objects.requireNonNull(inputs);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public JobPage list(Actor actor,String kind,String target,long page,int size){
        owner(actor);return read(()->{var nativePage=views.page(kind,target,page,size);var projection=new Projection();var result=new ArrayList<Job>();for(var row:nativePage.items())result.add(projection.job(row));return new JobPage(List.copyOf(result),page,size,nativePage.total());});
    }
    public Job get(Actor actor,String id){
        owner(actor);WireValues.id(id,false);return read(()->{var row=views.job(id);if(row==null)throw new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null);return new Projection().job(row);});
    }
    private <T>T read(Supplier<T> work){try{return reads.execute(s->work.get());}catch(IdentityService.Rejected refused){throw refused;}catch(RuntimeException failed){throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}}
    private final class Projection {
        final JdbcOwnerJobViews.Slot slot=views.slot();
        final Map<String,OwnerJobInputMetadataService.Metadata> metadata=new HashMap<>();
        final Map<String,Row> roots=new HashMap<>();
        long inputBytes;
        Projection(){if(slot.occupiedAttemptId()!=null){var attempt=views.job(slot.occupiedAttemptId());if(attempt==null||!"ai_attempt".equals(attempt.kind()))throw invalid();}}
        OwnerJobInputMetadataService.Metadata input(Row root){
            if(!metadata.containsKey(root.id())){var value=inputs.read(root.id());if(value!=null){inputBytes+=value.inputBytes();if(inputBytes>16L*1024*1024)throw invalid();}metadata.put(root.id(),value);}return metadata.get(root.id());
        }
        Row root(String id){if(id==null)throw invalid();if(!roots.containsKey(id)){var row=views.job(id);if(row==null||!"logical_task".equals(row.kind()))throw invalid();roots.put(id,row);}return roots.get(id);}
        Job job(Row row){
            boolean logical="logical_task".equals(row.kind()),attempt="ai_attempt".equals(row.kind()),intent="generation_intent".equals(row.kind());
            var summaries=new ArrayList<AttemptSummary>();String intentId=intent?row.id():row.intentId(),content=null,snapshot=null,external=null;Coverage coverage;java.time.Instant cutoff=null;
            if(logical||attempt){
                Row original=logical?row:root(row.rootId());var value=input(original);coverage=value==null?pending(original.id()):value.coverage();if(value!=null){content=value.contentId();snapshot=value.inputSnapshotId();cutoff=original.cutoff();}intentId=original.intentId();
                List<Row> attempts=logical?views.attempts(row.id()):List.of(row);for(var item:attempts){if(!original.id().equals(item.rootId())||!original.id().equals(item.snapshotOwnerId())||item.intentId()!=null&&!Objects.equals(original.intentId(),item.intentId()))throw invalid();summaries.add(new AttemptSummary(item.id(),item.attemptNo(),item.state(),usage(item),item.releasedAt(),item.releaseReason()));String observed=external(item.state());if(observed!=null)external=observed;}
            }else if(intent){
                var originalRoots=views.roots(row.id());var coverages=new ArrayList<Coverage>();var contents=new HashSet<String>();boolean allContent=!originalRoots.isEmpty();
                for(var original:originalRoots){roots.put(original.id(),original);var value=input(original);coverages.add(value==null?pending(original.id()):value.coverage());if(value==null||value.contentId()==null)allContent=false;else contents.add(value.contentId());}
                coverage=coverages.isEmpty()?pending(row.id()):merge(coverages);if(allContent&&contents.size()==1)content=contents.iterator().next();
            }else coverage=new Coverage("no_input",List.of(),List.of(),List.of());
            Long intentRevision=views.intentRevision(intentId);long calls=logical?row.callCount():0,remaining=logical?3-calls:0;boolean exhausted=logical&&calls==3;
            var waiting=new ArrayList<String>();if(Set.of("waiting_input","waiting_retry","blocked_configuration","unknown_external","result_conflict","cancel_requested").contains(row.state()))waiting.add(row.state());if((logical||intent)&&coverage.missingInputs().stream().anyMatch(m->"not_frozen".equals(m.reason()))&&!waiting.contains("waiting_input"))waiting.add("waiting_input");if(exhausted)waiting.add("retry_exhausted");
            String inputState=(logical||attempt||intent)?row.inputState():null;if(inputState!=null&&!Set.of("ready","no_input","waiting_input").contains(inputState))throw invalid();if(logical&&inputState==null)inputState="waiting_input";
            if(row.reportMode()!=null&&!Set.of("normal","no_updates","coverage_only").contains(row.reportMode()))throw invalid();
            return new Job(row.id(),row.kind(),row.revision(),row.taskType(),row.priority(),row.state(),intentId,intentRevision,content,row.reportMode(),inputState,snapshot,cutoff,coverage,logical?row.eligibleAt():null,calls,logical?3L:null,remaining,exhausted,slot.state(),slot.revision(),slot.occupiedAttemptId(),external,row.releasedAt(),row.releaseReason(),List.copyOf(summaries),List.copyOf(waiting));
        }
    }
    private static Coverage pending(String id){return new Coverage("partial",List.of(new MissingInput("input_snapshot",id,null,"not_frozen")),List.of(),List.of());}
    private static Coverage merge(List<Coverage> values){
        var missing=new LinkedHashSet<MissingInput>();var dates=new TreeSet<LocalDate>();var noUpdates=new TreeSet<LocalDate>();boolean complete=true,noInput=true;
        for(var value:values){complete&="complete".equals(value.state());noInput&="no_input".equals(value.state());missing.addAll(value.missingInputs());dates.addAll(value.missingDates());noUpdates.addAll(value.noUpdateDates());if(missing.size()>1000||dates.size()>366||noUpdates.size()>366)throw invalid();}
        if(!Collections.disjoint(dates,noUpdates))throw invalid();return new Coverage(complete?"complete":noInput?"no_input":"partial",List.copyOf(missing),List.copyOf(dates),List.copyOf(noUpdates));
    }
    private static String external(String state){return switch(state){case "unknown_external","unknown"->"unknown";case "succeeded"->"succeeded";case "failed"->"failed";default->null;};}
    private static Usage usage(Row row){
        Long input=null,output=null;if(row.usageJson()!=null){JsonNode value=CanonicalJson.parse(row.usageJson());if(!value.isObject())throw invalid();input=token(value,"input_tokens","prompt_tokens");output=token(value,"output_tokens","completion_tokens");}
        String cost=row.cost();if(cost!=null&&(!cost.matches("[0-9]+(?:\\.[0-9]+)?")||new BigDecimal(cost).signum()<0))throw invalid();return input==null&&output==null&&cost==null?null:new Usage(input,output,cost);
    }
    private static Long token(JsonNode node,String field,String provider){Long a=number(node.get(field)),b=number(node.get(provider));if(a!=null&&b!=null&&!a.equals(b))throw invalid();return a==null?b:a;}
    private static Long number(JsonNode value){return value==null||value.isNull()?null:WireValues.nonnegativeInteger(value);}
    private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static IllegalStateException invalid(){return new IllegalStateException("owner job metadata unavailable");}
}
