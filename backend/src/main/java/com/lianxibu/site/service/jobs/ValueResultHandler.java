package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.articles.ValueApplicationService;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import java.util.function.BiFunction;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Frozen value batch plus downstream intents in the caller's shared transaction. */
public final class ValueResultHandler implements Handler {
    public interface Downstream {void afterApplied(Input input,Validated result,List<Value> values);}
    private final ValueApplicationService values;
    private final BiFunction<String,String,String> snapshots;
    private final Downstream downstream;
    private final SourceDedupDependencyService sourceDedup;
    private final SourceDedupPartialDependencyPermitService partialPermits;
    private final TransactionTemplate reads;
    private final Input capturedInput;
    private final SourceDedupPartialDependencyPermitService.Captured permission;
    public ValueResultHandler(ValueApplicationService values,BiFunction<String,String,String> snapshots,Downstream downstream) {
        this(values,snapshots,downstream,null);
    }
    public ValueResultHandler(ValueApplicationService values,BiFunction<String,String,String> snapshots,Downstream downstream,SourceDedupDependencyService sourceDedup) {
        this.values=Objects.requireNonNull(values);this.snapshots=Objects.requireNonNull(snapshots);this.downstream=Objects.requireNonNull(downstream);this.sourceDedup=sourceDedup;
        partialPermits=null;reads=null;capturedInput=null;permission=null;
    }
    /** Actual partial permission is explicit and is captured only outside transactions. */
    public ValueResultHandler(ValueApplicationService values,BiFunction<String,String,String> snapshots,Downstream downstream,SourceDedupDependencyService sourceDedup,SourceDedupPartialDependencyPermitService partialPermits,PlatformTransactionManager tx){
        this.values=Objects.requireNonNull(values);this.snapshots=Objects.requireNonNull(snapshots);this.downstream=Objects.requireNonNull(downstream);this.sourceDedup=Objects.requireNonNull(sourceDedup);this.partialPermits=Objects.requireNonNull(partialPermits);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);capturedInput=null;permission=null;
    }
    private ValueResultHandler(ValueResultHandler original,Input input,SourceDedupPartialDependencyPermitService.Captured permission){
        values=original.values;snapshots=original.snapshots;downstream=original.downstream;sourceDedup=original.sourceDedup;partialPermits=original.partialPermits;reads=original.reads;capturedInput=input;this.permission=permission;
    }
    private record Frame(Input input,String profile,Instant cutoff){ }
    public ValueResultHandler capture(Input input){
        try{
            if(reads==null||capturedInput!=null||TransactionSynchronizationManager.isActualTransactionActive()||input==null||input.stage()!=GenerationFlowService.Stage.NEWS_VALUE)throw new Rejected();
            var frame=reads.execute(s->{
                var snapshot=CanonicalJson.parse(snapshots.apply(input.rootId(),input.inputHash()));NewsValueInputProfile.complete(snapshot);
                if(!input.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot))||!input.rootId().equals(SourcePartitionPlanner.rootId(snapshot.get("logical_key").asText(),snapshot.get("generation").longValue(),snapshot.get(SourcePartitionPlanner.FIELD).get("stage_part").longValue())))throw new Rejected();
                // The context creates immutable sets and maps, independent of the caller's Input.
                var original=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision());if(!original.equals(input))throw new Rejected();
                var profile=snapshot.get(FrozenSourceDedupDependency.FIELD);
                return new Frame(original,"source-dedup-dependency-v2".equals(profile.get("schema_version").asText())?com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(profile):null,Instant.parse(snapshot.get("cutoff").asText()));
            });
            Objects.requireNonNull(frame);if(frame.profile()==null)return this;
            var captured=partialPermits.capture(CanonicalJson.parse(frame.profile()),frame.cutoff());if(captured==null||!frame.profile().equals(captured.dependency().json()))throw new Rejected();
            return new ValueResultHandler(this,frame.input(),captured);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw new Rejected();}
    }
    @Override public Applied apply(Input input,Validated result,Current current,boolean manual) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("value result requires shared write transaction");
        if(input==null||result==null||current==null||input.stage()!=GenerationFlowService.Stage.NEWS_VALUE||!"value".equals(result.kind())||!current.dependenciesValid())throw new Rejected();
        var snapshot=CanonicalJson.parse(snapshots.apply(input.rootId(),input.inputHash()));var members=FrozenMemberBindings.read(snapshot,input);
        boolean complete=sourceDedup!=null||snapshot.has(FrozenSourceDedupDependency.FIELD)||snapshot.has(NewsValueInputProfile.ELIGIBILITY_FIELD);
        boolean valueV2="2".equals(input.valueResultVersion());if(valueV2&&!complete)throw new Rejected();
        Map<String,String> entityIds=new HashMap<>();
        if(complete) {
            if(sourceDedup==null)throw new Rejected();NewsValueInputProfile.complete(snapshot);
            var profile=snapshot.get(FrozenSourceDedupDependency.FIELD);var cutoff=Instant.parse(snapshot.get("cutoff").asText());
            if("source-dedup-dependency-v2".equals(profile.get("schema_version").asText())){
                if(capturedInput==null||permission==null||!capturedInput.equals(input)||!input.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw new Rejected();
                var proven=sourceDedup.readPartial(permission,cutoff);if(!proven.dependency().json().equals(com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(profile)))throw new Rejected();
            }else sourceDedup.read(profile,cutoff);
            for(var member:snapshot.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members"))entityIds.put(member.get("item_id").asText(),member.get("entity_id").asText());
            if(!entityIds.keySet().equals(members.keySet()))throw new Rejected();
        }
        var envelope=JsonMapper.shared().createObjectNode();envelope.put("schema_version","1");envelope.put("input_hash",result.inputHash());envelope.put("result_hash",result.resultHash());envelope.set("payload",CanonicalJson.parse(result.payloadJson()));
        if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw new Rejected();
        var payload=envelope.get("payload");Set<String> selected=new HashSet<>();payload.get("selected_item_ids").forEach(id->selected.add(id.asText()));
        List<Value> batch=new ArrayList<>();
        for(var item:payload.get("items")) {
            String id=item.get("item_id").asText();var frozen=members.get(id);
            String classification=item.get("category").asText();
            if(!complete&&!frozen.category().equals(classification))throw new Rejected();
            batch.add(new Value(id,frozen.expectedRevision(),classification,item.get("high_value").booleanValue(),selected.contains(id),item.get("reason").asText(),frozen.category(),complete?entityIds.get(id):null,valueV2?item.get("valuable").booleanValue():null));
        }
        values.applyInTransaction(input.rootId(),input.inputHash(),result.resultHash(),List.copyOf(batch));
        downstream.afterApplied(input,result,List.copyOf(batch));return new Applied(null,null);
    }
    @Override public String toString(){return "ValueResultHandler[redacted]";}
}
