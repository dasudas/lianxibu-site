package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcContentReads.Row;
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
    private Current observed;
    public ResultJdbcStore(DataSource source,Identity identity,ContentReadService contents,
                           ReferenceProof references,Clock clock,String actor){
        state=new JdbcResultState(Objects.requireNonNull(source));inputs=new JdbcFrozenInputs(source);
        this.identity=Objects.requireNonNull(identity);this.contents=Objects.requireNonNull(contents);
        this.references=Objects.requireNonNull(references);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);
    }
    public FrozenApplicationContext context(){
        state.claim(identity);return FrozenApplicationContext.fromJson(CanonicalJson.parse(inputs.read(identity.rootId(),identity.inputHash())).get(FrozenApplicationContext.FIELD));
    }
    public Input input(){var claim=state.claim(identity);return context().input(identity.rootId(),identity.attemptId(),identity.inputHash(),claim.path("root_revision").asLong());}
    @Override public void verifyInput(Input value){if(!input().equals(value))throw new Rejected();}
    @Override public Result committed(String key,String actionHash){
        var receipt=state.receipt(key,actor,identity.rootId(),identity.attemptId());if(receipt==null)return null;
        if(!"result-application-receipt-v1".equals(receipt.path("schema_version").asText())||!actionHash.equals(receipt.path("action_hash").asText())||!identity.inputHash().equals(receipt.path("input_hash").asText()))throw JdbcResultState.conflict();
        var retained=retained(identity.rootId());if(retained==null||!retained.resultHash().equals(receipt.path("result_hash").asText()))throw JdbcResultState.conflict();
        return new Result(Status.valueOf(receipt.path("status").asText()),retained.resultHash(),nullable(receipt,"content_id"),receipt.path("content_revision").isNull()?null:WireValues.nonnegativeInteger(receipt.get("content_revision")));
    }
    @Override public Current current(String rootId){
        exact(rootId);var root=state.root(rootId);var claim=state.claim(identity);var ctx=context();var row=content(ctx);
        boolean valid=ctx.referencesComplete()&&ctx.referenceTargets().stream().allMatch(references::valid);
        observed=new Current(root.generation(),root.hash(),root.revision(),row==null?null:row.revision(),valid,Set.of("discarded","cancelled").contains(root.state()),root.appliedAt()==null?null:root.resultHash(),root.resultHash(),identity.attemptId(),state.owns(identity,claim,clock.instant()),root.application());return observed;
    }
    @Override public void retain(String rootId,String attemptId,Validated result){
        JdbcFrozenInputs.requireTx();exact(rootId);if(!identity.attemptId().equals(attemptId)||!identity.inputHash().equals(result.inputHash()))throw new Rejected();
        var validated=ResultApplicationService.validate(input(),envelope(result));if(!validated.equals(result))throw new Rejected();
        var claim=state.claim(identity);if(!state.owns(identity,claim,clock.instant()))throw new Rejected();
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
        JdbcFrozenInputs.requireTx();exact(rootId);if(observed==null)throw new Rejected();var root=state.root(rootId);var claim=state.claim(identity);
        if(root.revision()!=observed.rootRevision()||root.appliedAt()!=null||!hash.equals(root.resultHash())||!Objects.equals(root.hash(),observed.inputHash())||root.generation()!=observed.generation())throw new Rejected();
        // Automatic completion rechecks ownership after the handler. Manual application
        // must leave a subsequently occupied slot alone.
        if(observed.ownershipValid()){if(!state.owns(identity,claim,clock.instant()))throw new Rejected();}
        else if(!Status.CONTENT_REVISION_CONFLICT.name().equals(root.application()))throw new Rejected();
        var receipt=JsonMapper.shared().createObjectNode();receipt.put("schema_version","result-application-receipt-v1");receipt.put("action_hash",actionHash);receipt.put("input_hash",identity.inputHash());receipt.put("status",status.name());receipt.put("result_hash",hash);receipt.put("content_id",contentId);if(revision==null)receipt.putNull("content_revision");else receipt.put("content_revision",revision);
        if(status==Status.APPLIED){
            var ctx=context();if(!ctx.referencesComplete()||!ctx.referenceTargets().stream().allMatch(references::valid))throw new Rejected();
            if(ctx.contentId()!=null){var row=content(ctx);if(!ctx.contentId().equals(contentId)||row==null||revision==null||revision!=row.revision()||!identity.rootId().equals(row.inputJobId())||row.bodyHashV1()==null)throw new Rejected();
                var version=JsonMapper.shared().createObjectNode();version.put("kind","content_version");version.put("content_kind",ctx.contentKind());version.put("content_id",contentId);version.put("revision",row.effectiveBodyRevision());version.put("body_hash",HexFormat.of().formatHex(row.bodyHashV1()));version.put("input_job_id",row.inputJobId());ContentVersionKey.fromJson(version);receipt.set("content_version",version);
            }else if(contentId!=null||revision!=null)throw new Rejected();
        }
        String next=switch(status){case APPLIED->"succeeded";case CONTENT_REVISION_CONFLICT->"result_conflict";case SUPERSEDED->"superseded";case INVALID_EVIDENCE->"blocked";case DISCARDED->"discarded";default->throw new Rejected();};
        state.finish(root,identity,claim,hash,status.name(),next,status==Status.APPLIED,clock.instant());state.append(key,actor,identity,status.name(),receipt,clock.instant());
    }
    private Row content(FrozenApplicationContext ctx){if(ctx.contentId()==null)return null;var row=contents.adminCurrent(ctx.contentKind(),ctx.contentId()).orElse(null);if(row!=null&&(!ctx.contentKind().equals(row.kind())||!ctx.contentId().equals(row.id())))throw JdbcResultState.conflict();return row;}
    private void exact(String rootId){if(!identity.rootId().equals(rootId))throw new Rejected();}
    private static String envelope(Validated result){var node=JsonMapper.shared().createObjectNode();node.put("schema_version","1");node.put("input_hash",result.inputHash());node.set("payload",CanonicalJson.parse(result.payloadJson()));node.put("result_hash",result.resultHash());return JdbcFrozenInputs.canonical(node);}
    private static String nullable(JsonNode node,String key){return node.path(key).isNull()?null:WireValues.id(node.get(key),false);}
}
