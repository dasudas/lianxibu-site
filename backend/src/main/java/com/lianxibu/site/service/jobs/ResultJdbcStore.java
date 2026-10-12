package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService.CurrentVersion;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.Identity;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Per-attempt result adapter. Reference verification is an explicit dependency;
 * installing this provider does not assemble handlers or enable production dispatch. */
public final class ResultJdbcStore implements Store {
    @FunctionalInterface public interface ReferenceProof { boolean valid(JsonNode exactTarget); }
    private final JdbcResultState state;
    private final JdbcFrozenInputs inputs;
    private final Identity identity;
    private final ContentReadService contents;
    private final ReferenceProof references;
    private final Clock clock;
    private final String actor;
    private final boolean manualProof;
    private final JdbcOwnerRetainedResultReads original;
    private Current observed;
    public ResultJdbcStore(DataSource source,Identity identity,ContentReadService contents,
                           ReferenceProof references,Clock clock,String actor){this(source,identity,contents,references,clock,actor,false);}
    public ResultJdbcStore(DataSource source,Identity identity,ContentReadService contents,
                           ReferenceProof references,Clock clock,String actor,boolean manualProof){
        this.manualProof=manualProof;original=new JdbcOwnerRetainedResultReads(Objects.requireNonNull(source));
        state=new JdbcResultState(Objects.requireNonNull(source));inputs=new JdbcFrozenInputs(source);
        this.identity=Objects.requireNonNull(identity);this.contents=Objects.requireNonNull(contents);
        this.references=Objects.requireNonNull(references);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);
    }
    public FrozenApplicationContext context(){
        state.claim(proofIdentity());return FrozenApplicationContext.fromJson(CanonicalJson.parse(inputs.read(identity.rootId(),identity.inputHash())).get(FrozenApplicationContext.FIELD));
    }
    public Input input(){var claim=state.claim(proofIdentity());return context().input(identity.rootId(),identity.attemptId(),identity.inputHash(),claim.path("root_revision").asLong());}
    @Override public boolean retainAfterAutomaticFailure(Input value){
        if(manualProof||value==null)return false;
        if("2".equals(value.dedupResultVersion())&&Set.of(GenerationFlowService.Stage.AGENT_DEDUP,GenerationFlowService.Stage.SKILL_DEDUP,GenerationFlowService.Stage.WORKFLOW_DEDUP,GenerationFlowService.Stage.MODEL_DEDUP).contains(value.stage()))return true;
        if(value.stage()==null||!(value.stage().analysis()||value.stage().report()))return false;
        try{
            if(!input().equals(value))return false;var snapshot=CanonicalJson.parse(inputs.read(value.rootId(),value.inputHash()));FrozenApplicationContext context;
            if(value.stage().report()){if(!snapshot.has(ReportPreparationInputProfile.FIELD))return false;var full=ReportPreparationInputProfile.complete(snapshot);var command=full.command();if(!JdbcReportPreparedInputs.root(command.logicalKey(),command.generation()).equals(value.rootId()))return false;context=command.applicationContext();}
            else{if(!snapshot.has(CatalogAnalysisInputProfile.FIELD))return false;context=CatalogAnalysisInputProfile.complete(snapshot).command().applicationContext();}
            return value.equals(context.input(value.rootId(),value.attemptId(),value.inputHash(),value.expectedRootRevision()));
        }catch(RuntimeException unavailable){return false;}
    }
    @Override public void verifyInput(Input value){if(!input().equals(value))throw new Rejected();}
    @Override public Result committed(String key,String actionHash){
        JsonNode receipt;
        try{receipt=state.receipt(key,actor,identity.rootId(),identity.attemptId());}
        catch(JdbcResultState.ReceiptIdentityConflict conflict){if(manualProof)throw new ManualRejected(ManualRefusal.IDENTITY_CONFLICT);throw conflict;}
        if(receipt==null)return null;
        var required=Set.of("schema_version","action_hash","input_hash","status","result_hash","content_id","content_revision");var allowed=new HashSet<>(required);allowed.add("content_version");WireValues.object(receipt,allowed,required);
        if(!"result-application-receipt-v1".equals(WireValues.text(receipt.get("schema_version")))||!identity.inputHash().equals(WireValues.sha256(receipt.get("input_hash"))))throw JdbcResultState.conflict();
        String originalActionHash=WireValues.sha256(receipt.get("action_hash"));var retained=retained(identity.rootId());
        if(retained==null||!retained.resultHash().equals(WireValues.sha256(receipt.get("result_hash"))))throw JdbcResultState.conflict();
        Status status=Status.valueOf(WireValues.text(receipt.get("status")));if(status==Status.REPLAYED)throw JdbcResultState.conflict();
        String contentId=nullable(receipt,"content_id");Long revision=receipt.path("content_revision").isNull()?null:WireValues.nonnegativeInteger(receipt.get("content_revision"));
        if(status==Status.APPLIED){var context=context();
            if(context.contentId()!=null){
                if(!context.contentId().equals(contentId)||revision==null||revision<1||!receipt.has("content_version"))throw JdbcResultState.conflict();
                var version=ContentVersionKey.fromJson(receipt.get("content_version"));var body=CanonicalJson.parse(retained.payloadJson()).get("body");
                if(!context.contentKind().equals(version.contentKind())||!contentId.equals(version.contentId())||!identity.rootId().equals(version.inputJobId())||version.revision()<1||version.revision()>revision
                        ||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body).equals(version.bodyHash()))throw JdbcResultState.conflict();
            }else if(contentId!=null||revision!=null||receipt.has("content_version"))throw JdbcResultState.conflict();
        }else if(contentId!=null||receipt.has("content_version"))throw JdbcResultState.conflict();
        if(!actionHash.equals(originalActionHash)){if(manualProof)throw new ManualRejected(ManualRefusal.IDENTITY_CONFLICT);throw JdbcResultState.conflict();}
        return new Result(status,retained.resultHash(),contentId,revision);
    }
    @Override public Current current(String rootId){
        exact(rootId);var root=state.root(rootId);var claim=state.claim(proofIdentity());var ctx=context();
        if(manualProof)manualFrame(ctx,rootId);
        var row=content(ctx);if(manualProof&&ctx.contentId()!=null&&row==null)throw new Rejected();
        boolean valid=ctx.referencesComplete()&&ctx.referenceTargets().stream().allMatch(references::valid);
        observed=new Current(root.generation(),root.hash(),root.revision(),row==null?null:row.revision(),valid,Set.of("discarded","cancelled").contains(root.state()),root.appliedAt()==null?null:root.resultHash(),root.resultHash(),identity.attemptId(),state.owns(proofIdentity(),claim,clock.instant()),root.application());return observed;
    }
    @Override public void retain(String rootId,String attemptId,Validated result){
        JdbcFrozenInputs.requireTx();exact(rootId);if(!identity.attemptId().equals(attemptId)||!identity.inputHash().equals(result.inputHash()))throw new Rejected();
        var validated=ResultApplicationService.validate(input(),envelope(result));if(!validated.equals(result))throw new Rejected();
        var claim=state.claim(proofIdentity());if(!state.owns(proofIdentity(),claim,clock.instant()))throw new Rejected();
        var root=state.root(rootId);if(root.resultHash()!=null){if(!result.equals(retained(rootId)))throw new Rejected();return;}
        state.retain(root,attemptId,result.resultHash(),envelope(result),clock.instant());
    }
    @Override public Validated retained(String rootId){exact(rootId);var root=state.root(rootId);String raw=state.retained(root);if(raw==null)return null;var result=ResultApplicationService.validate(input(),raw);if(!result.resultHash().equals(root.resultHash()))throw JdbcResultState.conflict();return result;}
    @Override public void defer(String rootId,Status reason,String hash,String key,String actionHash){
        if(!Set.of(Status.CONTENT_REVISION_CONFLICT,Status.INVALID_EVIDENCE,Status.SUPERSEDED,Status.DISCARDED).contains(reason))throw new Rejected();
        finish(rootId,reason,hash,null,observed==null?null:observed.contentRevision(),key,actionHash);
    }
    @Override public void markApplied(String rootId,String hash,String contentId,Long revision,String key,String actionHash){finish(rootId,Status.APPLIED,hash,contentId,revision,key,actionHash);}
    private void finish(String rootId,Status status,String hash,String contentId,Long revision,String key,String actionHash){
        JdbcFrozenInputs.requireTx();exact(rootId);if(observed==null)throw new Rejected();var root=state.root(rootId);var claim=state.claim(proofIdentity());
        if(root.revision()!=observed.rootRevision()||root.appliedAt()!=null||!hash.equals(root.resultHash())||!Objects.equals(root.hash(),observed.inputHash())||root.generation()!=observed.generation())throw new Rejected();
        // Automatic completion rechecks ownership after the handler. Manual application
        // must leave a subsequently occupied slot alone.
        if(observed.ownershipValid()){if(!state.owns(proofIdentity(),claim,clock.instant()))throw new Rejected();}
        else if(!Set.of(Status.CONTENT_REVISION_CONFLICT.name(),"EXTERNAL_RESULT_CONFIRMED").contains(root.application()==null?"":root.application()))throw new Rejected();
        var receipt=JsonMapper.shared().createObjectNode();receipt.put("schema_version","result-application-receipt-v1");receipt.put("action_hash",actionHash);receipt.put("input_hash",identity.inputHash());receipt.put("status",status.name());receipt.put("result_hash",hash);receipt.put("content_id",contentId);if(revision==null)receipt.putNull("content_revision");else receipt.put("content_revision",revision);
        if(status==Status.APPLIED){
            var ctx=context();if(!ctx.referencesComplete()||!ctx.referenceTargets().stream().allMatch(references::valid))throw new Rejected();
            if(ctx.contentId()!=null){var row=content(ctx);if(!ctx.contentId().equals(contentId)||row==null||revision==null||revision!=row.revision()||!identity.rootId().equals(row.inputJobId())||row.bodyHash()==null)throw new Rejected();
                var version=JsonMapper.shared().createObjectNode();version.put("kind","content_version");version.put("content_kind",ctx.contentKind());version.put("content_id",contentId);version.put("revision",row.bodyRevision());version.put("body_hash",HexFormat.of().formatHex(row.bodyHash()));version.put("input_job_id",row.inputJobId());ContentVersionKey.fromJson(version);receipt.set("content_version",version);
            }else if(contentId!=null||revision!=null)throw new Rejected();
        }
        String next=switch(status){case APPLIED->"succeeded";case CONTENT_REVISION_CONFLICT->"result_conflict";case SUPERSEDED->"superseded";case INVALID_EVIDENCE->"blocked";case DISCARDED->"discarded";default->throw new Rejected();};
        state.finish(root,proofIdentity(),claim,hash,status.name(),next,status==Status.APPLIED,clock.instant());state.append(key,actor,proofIdentity(),status.name(),receipt,clock.instant());
    }
    private void manualFrame(FrozenApplicationContext ctx,String rootId){
        var frame=original.readForApplication(rootId);var id=frame==null?null:frame.identity();var expected=proofIdentity();
        if(id==null||!expected.rootId().equals(id.rootId())||!expected.attemptId().equals(id.attemptId())
                ||!expected.dispatchToken().equals(id.dispatchToken())||expected.slotRevision()!=id.slotRevision()||expected.fencing()!=id.fencing()
                ||!expected.leaseUntil().equals(id.leaseUntil())||!expected.inputHash().equals(id.inputHash())
                ||!Arrays.equals(CanonicalJson.bytes(CanonicalJson.parse(expected.frozenRequestJson())),CanonicalJson.bytes(CanonicalJson.parse(id.frozenRequestJson())))
                ||ctx.generation()!=frame.generation())throw JdbcResultState.conflict();
        var input=CanonicalJson.parse(frame.inputJson());
        if(!ctx.stage().name().equalsIgnoreCase(frame.root().taskType())||!frame.root().taskType().equals(WireValues.text(input.get("stage")))
                ||ctx.generation()!=WireValues.nonnegativeInteger(input.get("generation"))||!Objects.equals(frame.root().cutoff(),WireValues.utcMicroseconds(input.get("cutoff")))
                ||!Objects.equals(frame.root().coverageState(),WireValues.text(input.get("coverage_state")))||frame.root().missingJson()==null
                ||!CanonicalJson.parse(frame.root().missingJson()).equals(input.get("missing")))throw JdbcResultState.conflict();
        if(!ctx.json().equals(FrozenApplicationContext.fromJson(input.get(FrozenApplicationContext.FIELD)).json()))throw JdbcResultState.conflict();
        if(ctx.stage().analysis()||ctx.stage().report())com.lianxibu.site.service.content.FrozenPublicMetadata.read(input.get(com.lianxibu.site.service.content.FrozenPublicMetadata.FIELD),ctx);
        if(ctx.stage().report()){
            com.lianxibu.site.service.content.ReportInputProfile.validated(input);
            var profile=input.get(com.lianxibu.site.service.content.ReportInputProfile.FIELD);
            if(!ctx.periodStart().equals(profile.path("period_start").asText())||!ctx.periodEnd().equals(profile.path("period_end").asText()))throw JdbcResultState.conflict();
        }
    }
    private CurrentVersion content(FrozenApplicationContext ctx){if(ctx.contentId()==null)return null;var row=contents.applicationVersion(ctx.contentKind(),ctx.contentId()).orElse(null);
        if(manualProof){var proof=contents.provenApplicationVersion(ctx.contentKind(),ctx.contentId()).orElse(null);
            if(row==null!=(proof==null)||row!=null&&(row.revision()!=proof.revision()||row.bodyRevision()!=proof.bodyRevision()
                    ||!Objects.equals(row.inputJobId(),proof.inputJobId())||!Arrays.equals(row.bodyHash(),proof.bodyHash())))throw JdbcResultState.conflict();
        }if(row!=null&&(!ctx.contentKind().equals(row.kind())||!ctx.contentId().equals(row.id())))throw JdbcResultState.conflict();return row;}
    private JdbcResultState.Identity proofIdentity(){return new JdbcResultState.Identity(identity.rootId(),identity.attemptId(),identity.dispatchToken(),identity.slotRevision(),identity.fencing(),identity.leaseUntil(),identity.frozenRequestJson(),identity.inputHash());}
    private void exact(String rootId){if(!identity.rootId().equals(rootId))throw new Rejected();}
    private static String envelope(Validated result){var node=JsonMapper.shared().createObjectNode();node.put("schema_version","1");node.put("input_hash",result.inputHash());node.set("payload",CanonicalJson.parse(result.payloadJson()));node.put("result_hash",result.resultHash());return JdbcFrozenInputs.canonical(node);}
    private static String nullable(JsonNode node,String key){return node.path(key).isNull()?null:WireValues.id(node.get(key),false);}
}
