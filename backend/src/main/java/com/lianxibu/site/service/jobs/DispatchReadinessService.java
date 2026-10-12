package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.*;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** Revalidates the original input immediately before reservation and send authorization. */
public final class DispatchReadinessService {
    private final JdbcDispatchInputs roots;private final JdbcFrozenInputs inputs;
    private final FrozenDependencyIndexService dependencies;private final GenerationLineageService lineage;
    private final ContentReadService contents;private final SourceMemberEvidenceService members;
    private final ResultJdbcStore.ReferenceProof references;
    private final GenerationFlowService.CollectionProofSource collections;
    private final SourcePartitionProofService sourcePlans;
    private final boolean requireSourcePlans;
    private final SourceDedupDependencyService sourceDedup;
    private final SourceDedupPartialDependencyPermitService partialPermits;private final TransactionTemplate reads;
    private final SourceDayNewsValuePermitService dayPermits;
    private final ReportDispatchPermitService reports;private final Map<String,CatalogDayAnalysisDispatchPermitService> catalogPermits;
    public static final class Captured {
        private final String rootId,inputHash;private final SourceDedupPartialDependencyPermitService.Captured permit;private final SourceDayNewsValuePermitService.Captured day;private final CatalogDayAnalysisDispatchPermitService.Captured catalog;private final ReportDispatchPermitService.Captured report;
        private Captured(String rootId,String inputHash,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day){this(rootId,inputHash,permit,day,null);}
        private Captured(String rootId,String inputHash,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day,CatalogDayAnalysisDispatchPermitService.Captured catalog){this(rootId,inputHash,permit,day,catalog,null);}
        private Captured(String rootId,String inputHash,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day,CatalogDayAnalysisDispatchPermitService.Captured catalog,ReportDispatchPermitService.Captured report){this.rootId=rootId;this.inputHash=inputHash;this.permit=permit;this.day=day;this.catalog=catalog;this.report=report;if(permit==null&&day==null&&catalog==null&&report==null)throw unavailable();}
        @Override public String toString(){return day==null?"CapturedPartialDispatchInput[redacted]":"CapturedSourceDispatchInput[redacted]";}
    }
    private record Original(String rootId,String inputHash,Instant cutoff,JsonNode dependency,JsonNode day,String catalogCategory,boolean report){private Original(String rootId,String inputHash,Instant cutoff,JsonNode dependency,JsonNode day,String category){this(rootId,inputHash,cutoff,dependency,day,category,false);}}

    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references){
        this(roots,inputs,dependencies,lineage,contents,members,references,null,null,false);
    }
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections){
        this(roots,inputs,dependencies,lineage,contents,members,references,Objects.requireNonNull(collections),null,false);
    }
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections,SourcePartitionProofService sourcePlans,boolean requireSourcePlans){
        this(roots,inputs,dependencies,lineage,contents,members,references,collections,sourcePlans,requireSourcePlans,null);
    }
    /** Installing the original P1 provider requires complete P2 input, including native observation. */
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections,SourcePartitionProofService sourcePlans,boolean requireSourcePlans,SourceDedupDependencyService sourceDedup){
        this(roots,inputs,dependencies,lineage,contents,members,references,collections,sourcePlans,requireSourcePlans,sourceDedup,null,null);
    }
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections,SourcePartitionProofService sourcePlans,boolean requireSourcePlans,SourceDedupDependencyService sourceDedup,SourceDedupPartialDependencyPermitService partialPermits,PlatformTransactionManager tx){
        this(roots,inputs,dependencies,lineage,contents,members,references,collections,sourcePlans,requireSourcePlans,sourceDedup,partialPermits,tx,null);
    }
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections,SourcePartitionProofService sourcePlans,boolean requireSourcePlans,SourceDedupDependencyService sourceDedup,SourceDedupPartialDependencyPermitService partialPermits,PlatformTransactionManager tx,SourceDayNewsValuePermitService dayPermits){
        this(roots,inputs,dependencies,lineage,contents,members,references,collections,sourcePlans,requireSourcePlans,sourceDedup,partialPermits,tx,dayPermits,null);
    }
    /** Explicit whole-day catalog authority; all four category providers are mandatory. */
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections,SourcePartitionProofService sourcePlans,boolean requireSourcePlans,SourceDedupDependencyService sourceDedup,SourceDedupPartialDependencyPermitService partialPermits,PlatformTransactionManager tx,SourceDayNewsValuePermitService dayPermits,Map<String,CatalogDayAnalysisDispatchPermitService> catalogPermits){this(roots,inputs,dependencies,lineage,contents,members,references,collections,sourcePlans,requireSourcePlans,sourceDedup,partialPermits,tx,dayPermits,catalogPermits,null);}
    /** Formal report preparation is mandatory for report dispatch when installed. */
    public DispatchReadinessService(JdbcDispatchInputs roots,JdbcFrozenInputs inputs,FrozenDependencyIndexService dependencies,GenerationLineageService lineage,ContentReadService contents,SourceMemberEvidenceService members,ResultJdbcStore.ReferenceProof references,GenerationFlowService.CollectionProofSource collections,SourcePartitionProofService sourcePlans,boolean requireSourcePlans,SourceDedupDependencyService sourceDedup,SourceDedupPartialDependencyPermitService partialPermits,PlatformTransactionManager tx,SourceDayNewsValuePermitService dayPermits,Map<String,CatalogDayAnalysisDispatchPermitService> catalogPermits,ReportDispatchPermitService reports){
        this.reports=reports;if(reports!=null&&tx==null)throw unavailable();
        if(catalogPermits!=null&&(tx==null||!catalogPermits.keySet().equals(Set.of("agent","skill","workflow","model"))))throw unavailable();
        this.catalogPermits=catalogPermits==null?null:Map.copyOf(catalogPermits);
        this.roots=Objects.requireNonNull(roots);this.inputs=Objects.requireNonNull(inputs);this.dependencies=Objects.requireNonNull(dependencies);this.lineage=Objects.requireNonNull(lineage);this.contents=Objects.requireNonNull(contents);this.members=Objects.requireNonNull(members);this.references=Objects.requireNonNull(references);this.collections=collections;
        this.sourcePlans=requireSourcePlans?Objects.requireNonNull(sourcePlans):sourcePlans;this.requireSourcePlans=requireSourcePlans;this.sourceDedup=sourceDedup;this.partialPermits=partialPermits;
        this.dayPermits=dayPermits;if(dayPermits!=null&&(sourceDedup==null||tx==null))throw unavailable();
        reads=tx==null?null:new TransactionTemplate(tx);if(reads!=null){reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    }
    /** Compatibility entry; additional original source permissions may include a full P1 day. */
    public Captured capturePartial(String id){return captureSource(id);}
    public Captured captureSource(String id){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()||reads==null)throw unavailable();WireValues.id(id,false);
            var original=reads.execute(s->{
                JdbcSourceDedupResults.requireReadOnly();var root=roots.read(id);if(root==null||!id.equals(root.id())||root.inputHash()==null||root.inputHash().length!=32||root.cutoff()==null)throw unavailable();
                String hash=HexFormat.of().formatHex(root.inputHash());var input=CanonicalJson.parse(inputs.read(id,hash));
                if(Set.of("daily_report","weekly_report","monthly_report").contains(root.type())){if(reports==null){if(input.has(ReportPreparationInputProfile.FIELD))throw unavailable();return null;}ReportPreparationInputProfile.complete(input);return new Original(id,hash,root.cutoff(),null,null,null,true);}
                if(Set.of("agent_analysis","skill_analysis","workflow_analysis","model_analysis").contains(root.type())){
                    if(!input.has(CatalogAnalysisInputProfile.FIELD)){if(catalogPermits!=null)throw unavailable();return null;}
                    if(catalogPermits==null)throw unavailable();var profile=CatalogAnalysisInputProfile.complete(input);String category=profile.command().applicationContext().contentKind();if(!(category+"_analysis").equals(root.type()))throw unavailable();return new Original(id,hash,root.cutoff(),null,null,category);
                }
                if(input.has(CatalogAnalysisInputProfile.FIELD))throw unavailable();if(!"news_value".equals(root.type()))return null;
                boolean partial="source-dedup-dependency-v2".equals(input.path(FrozenSourceDedupDependency.FIELD).path("schema_version").asText());
                boolean day=input.has(FrozenSourceDayDedupDependency.FIELD);if(dayPermits!=null&&!day||day&&dayPermits==null)throw unavailable();
                if(!partial&&!day)return null;
                NewsValueInputProfile.complete(input);return new Original(id,hash,root.cutoff(),input.get(FrozenSourceDedupDependency.FIELD),input.get(FrozenSourceDayDedupDependency.FIELD),null);
            });
            if(original==null)return null;
            if(original.report()){var permit=reports.capture(original.rootId());if(permit==null||!original.rootId().equals(permit.rootId())||!original.inputHash().equals(permit.inputHash()))throw unavailable();return new Captured(original.rootId(),original.inputHash(),null,null,null,permit);}
            if(original.catalogCategory()!=null){var provider=catalogPermits.get(original.catalogCategory());var permit=provider.capture(original.rootId());if(permit==null||!original.rootId().equals(permit.rootId())||!original.inputHash().equals(permit.inputHash()))throw unavailable();return new Captured(original.rootId(),original.inputHash(),null,null,permit);}
            if(sourceDedup==null)throw unavailable();
            SourceDedupPartialDependencyPermitService.Captured permit=null;
            if("source-dedup-dependency-v2".equals(WireValues.text(original.dependency().get("schema_version")))){
                if(partialPermits==null)throw unavailable();permit=partialPermits.capture(original.dependency(),original.cutoff());
                if(permit==null||!JdbcFrozenInputs.canonical(original.dependency()).equals(permit.dependency().json()))throw unavailable();
            }
            SourceDayNewsValuePermitService.Captured day=null;if(original.day()!=null){
                if(dayPermits==null)throw unavailable();day=dayPermits.captureExisting(original.day(),original.dependency(),original.cutoff());
                if(day==null||!JdbcFrozenInputs.canonical(original.day()).equals(day.dependency().json()))throw unavailable();
            }
            return new Captured(original.rootId(),original.inputHash(),permit,day);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    public boolean ready(String id){return ready(id,null,false);}
    public boolean readyPartial(String id,Captured captured){return readyCaptured(id,captured);}
    public boolean readyCaptured(String id,Captured captured){if(captured==null)throw unavailable();return ready(id,captured,false);}
    /** Candidate preflight only. Actual collection and complete request proofs remain in the writer. */
    public boolean preflight(String id){return ready(id,null,true);}
    private boolean ready(String id,Captured captured,boolean observed){
        if(observed)JdbcSourceDedupResults.requireReadOnly();else JdbcFrozenInputs.requireTx();WireValues.id(id,false);var root=roots.read(id);
        if(root==null||!id.equals(root.id())||root.logicalKey()==null||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.logicalKey()))||root.latest()==null||root.generation()<1||root.latest()<root.generation())throw unavailable();
        if(root.latest()!=root.generation()||!Set.of("queued","waiting_retry","running").contains(root.state())||!"ready".equals(root.inputState()))return false;
        if(root.inputHash()==null||root.inputHash().length!=32||root.cutoff()==null||root.businessDate()==null||root.stagePart()<0||root.stagePart()>=10000)throw unavailable();
        String hash=HexFormat.of().formatHex(root.inputHash());if(captured!=null&&(!id.equals(captured.rootId)||!hash.equals(captured.inputHash)))throw unavailable();var snapshot=CanonicalJson.parse(inputs.read(id,hash));
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(snapshot.has("stage_part")?WireValues.nonnegativeInteger(snapshot.get("stage_part"))!=root.stagePart():root.stagePart()!=0)throw unavailable();
        if(root.stagePart()!=0&&!(context.stage().dedup()||context.stage()==GenerationFlowService.Stage.NEWS_VALUE))throw unavailable();
        if(!root.logicalKey().equals(WireValues.text(snapshot.get("logical_key")))||root.generation()!=WireValues.nonnegativeInteger(snapshot.get("generation"))||root.generation()!=context.generation()||!root.type().equals(context.stage().name().toLowerCase(Locale.ROOT))||!root.type().equals(WireValues.text(snapshot.get("stage")))||!root.businessDate().toString().equals(WireValues.text(snapshot.get("business_date")))||!root.cutoff().equals(Instant.parse(WireValues.text(snapshot.get("cutoff")))))throw unavailable();
        if(lineage.read(id).generation()!=root.generation())throw unavailable();dependencies.read(id,hash);
        if(!FrozenGenerationSettings.read(snapshot.get(FrozenGenerationSettings.FIELD)).semanticHash().equals(WireValues.text(snapshot.get("settings_hash"))))throw unavailable();
        if(observed)declaredCollection(snapshot,context.stage(),root.logicalKey(),root.generation(),root.businessDate());
        else CollectionInputProofs.verifyFrozen(snapshot,context.stage(),root.logicalKey(),root.generation(),root.businessDate(),collections);
        boolean sourceStage=context.stage().dedup()||context.stage()==GenerationFlowService.Stage.NEWS_VALUE;
        if(snapshot.has(SourcePartitionPlanner.FIELD)) {
            if(!sourceStage||sourcePlans==null)throw unavailable();var chunk=observed?sourcePlans.requireObserved(id,JdbcFrozenInputs.canonical(snapshot),hash):sourcePlans.require(id,JdbcFrozenInputs.canonical(snapshot),hash);if(!observed)originalRequests(root,chunk);
        }else if(sourceStage&&requireSourcePlans)throw unavailable();
        if(requireSourcePlans&&context.stage()==GenerationFlowService.Stage.NEWS_VALUE)NewsValueInputProfile.validated(snapshot);
        if(context.stage()==GenerationFlowService.Stage.NEWS_VALUE){
            var day=snapshot.get(FrozenSourceDayDedupDependency.FIELD);if(dayPermits!=null&&day==null||day!=null&&dayPermits==null)throw unavailable();
            if(day!=null&&!observed){
                if(captured==null||captured.day==null)throw unavailable();var actual=dayPermits.verify(captured.day,root.cutoff());
                if(!actual.json().equals(JdbcFrozenInputs.canonical(day)))throw unavailable();
            }else if(captured!=null&&captured.day!=null)throw unavailable();
        }else if(captured!=null&&captured.day!=null)throw unavailable();
        if(snapshot.has(FrozenSourceDedupDependency.FIELD)||snapshot.has(NewsValueInputProfile.ELIGIBILITY_FIELD)
                ||sourceDedup!=null&&context.stage()==GenerationFlowService.Stage.NEWS_VALUE) {
            if(context.stage()!=GenerationFlowService.Stage.NEWS_VALUE||sourceDedup==null)throw unavailable();NewsValueInputProfile.complete(snapshot);
            if("source-dedup-dependency-v2".equals(snapshot.path(FrozenSourceDedupDependency.FIELD).path("schema_version").asText())){
                if(!observed&&(captured==null||captured.permit==null||partialPermits==null))throw unavailable();var actual=observed?sourceDedup.readPartial(snapshot.get(FrozenSourceDedupDependency.FIELD),root.cutoff()):sourceDedup.readPartial(captured.permit,root.cutoff());
                if(!actual.dependency().json().equals(JdbcFrozenInputs.canonical(snapshot.get(FrozenSourceDedupDependency.FIELD))))throw unavailable();
            }else{if(captured!=null&&captured.permit!=null)throw unavailable();sourceDedup.read(snapshot.get(FrozenSourceDedupDependency.FIELD),root.cutoff());}
        }
        if(snapshot.has(CatalogAnalysisInputProfile.FIELD)){
            if(!context.stage().analysis()||catalogPermits==null)throw unavailable();CatalogAnalysisInputProfile.complete(snapshot);
            if(!observed){if(captured==null||captured.catalog==null)throw unavailable();if(!catalogPermits.get(context.contentKind()).verify(captured.catalog))return false;catalogRequests(root,captured.catalog.requestJson());}
        }else if(captured!=null&&captured.catalog!=null||catalogPermits!=null&&context.stage().analysis())throw unavailable();
        if(context.stage().report()&&(reports!=null||snapshot.has(ReportPreparationInputProfile.FIELD))){
            if(reports==null)throw unavailable();ReportPreparationInputProfile.complete(snapshot);
            if(observed){if(!reports.lookup(id))return false;}
            else{if(captured==null||captured.report==null)throw unavailable();var permit=reports.verifyInWriter(captured.report);if(!id.equals(permit.rootId())||!hash.equals(permit.inputHash()))throw unavailable();catalogRequests(root,permit.prepared().input().requestJson());}
        }else if(captured!=null&&captured.report!=null)throw unavailable();
        if(captured!=null&&context.stage()!=GenerationFlowService.Stage.NEWS_VALUE&&captured.catalog==null&&captured.report==null)throw unavailable();
        if(!context.referencesComplete()||!context.referenceTargets().stream().allMatch(references::valid))return false;
        if(context.stage().analysis()||context.stage().report()){
            FrozenPublicMetadata.read(snapshot.get(FrozenPublicMetadata.FIELD),context);
            if(context.stage().report())ReportInputProfile.validated(snapshot);
            var current=contents.applicationVersion(context.contentKind(),context.contentId()).orElse(null);
            if(current!=null&&(!context.contentKind().equals(current.kind())||!context.contentId().equals(current.id())))throw unavailable();
            if(!Objects.equals(context.baseContentRevision(),current==null?null:current.revision()))return false;
        }
        if(context.stage().dedup()||context.stage()==GenerationFlowService.Stage.NEWS_VALUE){
            var bindings=FrozenMemberBindings.read(snapshot,context.input(id,"dispatch-check",hash,root.revision()));
            var selected=snapshot.get("members");if(selected==null||!selected.isArray()||selected.size()!=bindings.size())throw unavailable();
            var material=new HashMap<String,JsonNode>();for(var item:selected)if(material.put(WireValues.id(item.get("id"),false),item)!=null)throw unavailable();
            if(!material.keySet().equals(bindings.keySet()))throw unavailable();
            var facts=observed?members.readObserved(bindings.keySet(),root.cutoff()):members.read(bindings.keySet(),root.cutoff());if(facts.size()!=bindings.size())throw unavailable();var seen=new HashSet<String>();
            for(var fact:facts){
                var binding=bindings.get(fact.id());if(binding==null||!seen.add(fact.id()))throw unavailable();
                if(binding.expectedRevision()!=fact.revision()||!binding.category().equals(fact.category())||!binding.identityKey().equals(fact.identityKey())||!binding.title().equals(fact.title())||!Objects.equals(binding.summary(),fact.summary()))return false;
                var item=material.get(fact.id());var body=CanonicalJson.parse(fact.payloadJson());
                if(!Arrays.equals(CanonicalJson.bytes(body),CanonicalJson.bytes(item.get("body")))||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body).equals(WireValues.text(item.get("body_hash"))))return false;
            }
        }
        return true;
    }
    private static void declaredCollection(JsonNode snapshot,GenerationFlowService.Stage stage,String key,long generation,java.time.LocalDate day){
        if(!stage.dedup()){if(snapshot.has(FrozenCollectionManifest.FIELD))throw unavailable();return;}
        var declared=FrozenCollectionManifest.read(snapshot.get(FrozenCollectionManifest.FIELD));if(declared.isEmpty())throw unavailable();String source=declared.getFirst().facts().sourceId();
        for(var proof:declared){var facts=proof.facts();if(!"production".equals(facts.activationStage())||!source.equals(facts.sourceId())||!day.equals(facts.businessDate())||generation!=facts.generation()||!stage.name().toLowerCase(Locale.ROOT).equals(CollectionHandoffProofService.stage(facts))||!key.equals(CollectionHandoffProofService.logicalKey(facts)))throw unavailable();}
    }
    private void catalogRequests(JdbcDispatchInputs.Root root,String original){
        if(root.calls()==null||root.maximum()==null||root.maximum()!=3||root.calls()<0||root.calls()>3)throw unavailable();boolean running="running".equals(root.state());int expected=running?root.calls():root.calls()+1;if(expected<1||expected>3)throw unavailable();var requests=roots.requests(root.id());if(requests.size()!=expected)throw unavailable();
        for(int n=0;n<requests.size();n++){var request=requests.get(n);WireValues.id(request.id(),false);if(!root.id().equals(request.root())||!root.id().equals(request.snapshotOwner())||!Integer.valueOf(n+1).equals(request.number())||!Arrays.equals(root.inputHash(),request.inputHash())||request.request()==null)throw unavailable();var full=CanonicalJson.parse(request.request());if(CanonicalJson.bytes(full).length>1048576||!original.equals(JdbcFrozenInputs.canonical(full)))throw unavailable();}
        if(!(running?"running":"prepared").equals(requests.getLast().state()))throw unavailable();
    }
    private void originalRequests(JdbcDispatchInputs.Root root,SourcePartitionPlanner.Chunk chunk) {
        if(root.calls()==null||root.maximum()==null||root.maximum()!=3||root.calls()<0||root.calls()>3)throw unavailable();
        boolean running="running".equals(root.state());int expected=running?root.calls():root.calls()+1;
        if(expected<1||expected>3)throw unavailable();var requests=roots.requests(root.id());if(requests.size()!=expected)throw unavailable();
        for(int n=0;n<requests.size();n++){
            var request=requests.get(n);WireValues.id(request.id(),false);
            if(!root.id().equals(request.root())||!root.id().equals(request.snapshotOwner())||!Integer.valueOf(n+1).equals(request.number())
                    ||!Arrays.equals(root.inputHash(),request.inputHash())||request.request()==null)throw unavailable();
            var nativeRequest=CanonicalJson.parse(request.request());
            if(CanonicalJson.bytes(nativeRequest).length>1048576||!chunk.requestJson().equals(JdbcFrozenInputs.canonical(nativeRequest)))throw unavailable();
        }
        if(!(running?"running":"prepared").equals(requests.getLast().state()))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("dispatch input proof unavailable");}
}
