package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** Owner authorization and full original INPUT semantics around the permanent external ledger. */
public final class OwnerJobResolveExternalService {
    private final JdbcOwnerExternalAttemptReads originals;
    private final JdbcOwnerExternalResolution ledger;
    private final SharedWriteExecutor writes;
    private final TransactionTemplate reads;
    private final Clock clock;
    public OwnerJobResolveExternalService(DataSource source,PlatformTransactionManager tx,SharedWriteExecutor writes,Clock clock){
        Objects.requireNonNull(source);originals=new JdbcOwnerExternalAttemptReads(source);ledger=new JdbcOwnerExternalResolution(source);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public JdbcOwnerExternalResolution.Resolved resolve(Actor actor,String root,JobAction command,String requestKey){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(root,false);WireValues.id(actor.userId(),false);
        if(command==null||!"resolve_external".equals(command.action())||command.attemptId()==null||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid external verification command");
        WireValues.id(command.attemptId(),false);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("owner external verification must own its transaction");
        var frame=reads.execute(s->originals.read(root,command.attemptId()));if(frame==null)throw new JobRejected(JobRefusal.NOT_FOUND);
        var evidence=OwnerExternalResolutionMaterial.verify(actor,root,command,frame).evidence();
        var frozen=new JdbcOwnerExternalResolution.Command(evidence.canonical(),frame.identity().inputHash());String key=OwnerJobCancelService.actionKey(requestKey);
        try{return writes.execute(new SharedWriteExecutor.ActionContext(key,root,frozen.inputHash()),k->ledger.committed(k,frozen,frame,this::verifyNativeEvidence),a->ledger.resolve(a.actionKey(),frozen,clock.instant(),this::verifyNativeEvidence));}
        catch(JdbcOwnerExternalResolution.Rejected refused){throw new JobRejected(switch(refused.getMessage()){case "not_found"->JobRefusal.NOT_FOUND;case "revision_conflict"->JobRefusal.REVISION_CONFLICT;case "run_frozen"->JobRefusal.RUN_FROZEN;case "invalid_evidence"->JobRefusal.INVALID_EVIDENCE;case "identity_conflict"->JobRefusal.IDENTITY_CONFLICT;default->throw unavailable();});}
    }
    /** Old authenticated operator is part of immutable evidence; the new caller is separately authorized. */
    private void verifyNativeEvidence(JdbcOwnerExternalAttemptReads.Frame frame,String raw){
        try{
            var value=CanonicalJson.parse(raw);closed(value,"schema_version","root_id","attempt_id","operator_id","expected_revision","external_state","reason","evidence_refs","result","usage");
            if(!"owner-external-resolution-evidence-v1".equals(WireValues.text(value.get("schema_version"))))throw unavailable();
            var refs=new ArrayList<String>();var array=value.get("evidence_refs");if(!array.isArray())throw unavailable();for(var ref:array)refs.add(WireValues.id(ref,false));
            var result=value.get("result");ValidatedResult validated=null;if(!result.isNull()){closed(result,"schema_version","input_hash","payload","result_hash");validated=new ValidatedResult(WireValues.text(result.get("schema_version")),WireValues.sha256(result.get("input_hash")),result.get("payload"),WireValues.sha256(result.get("result_hash")));}
            var usage=value.get("usage");Usage known=null;if(!usage.isNull()){closed(usage,"input_tokens","output_tokens","cost");known=new Usage(number(usage.get("input_tokens")),number(usage.get("output_tokens")),usage.get("cost").isNull()?null:WireValues.text(usage.get("cost")));}
            var action=new JobAction(WireValues.nonnegativeInteger(value.get("expected_revision")),"resolve_external",WireValues.text(value.get("reason")),WireValues.id(value.get("attempt_id"),false),null,null,WireValues.text(value.get("external_state")),List.copyOf(refs),validated,known);
            var operator=new Actor(WireValues.id(value.get("operator_id"),false),"owner");var proof=OwnerExternalResolutionMaterial.verify(operator,WireValues.id(value.get("root_id"),false),action,frame);
            if(!raw.equals(proof.evidence().canonical()))throw unavailable();
        }catch(RuntimeException corrupt){throw unavailable();}
    }
    private static Long number(JsonNode value){return value.isNull()?null:WireValues.nonnegativeInteger(value);}
    private static void closed(JsonNode value,String...fields){var names=Set.of(fields);WireValues.object(value,names,names);}
    private static IllegalStateException unavailable(){return new IllegalStateException("original owner verification semantics unavailable");}
}
