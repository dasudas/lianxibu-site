package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.JobAction;
import com.lianxibu.site.service.jobs.ResultApplicationService.Input;
import com.lianxibu.site.service.jobs.ResultApplicationService.Validated;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Immutable owner verification material. Native attempt proof and persistence are separate ports. */
public final class OwnerExternalResolutionEvidence {
    private final String canonical,actionHash,rootId,attemptId,operatorId,externalState;
    private final long expectedRevision;
    private final Validated result;
    private OwnerExternalResolutionEvidence(String canonical,String root,String attempt,String actor,long revision,String state,Validated result){
        this.canonical=canonical;this.actionHash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(canonical));
        this.rootId=root;this.attemptId=attempt;this.operatorId=actor;this.expectedRevision=revision;this.externalState=state;this.result=result;
    }
    public static OwnerExternalResolutionEvidence verify(Actor actor,String root,JobAction command,Input original){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(actor.userId(),false);WireValues.id(root,false);
        if(command==null||original==null||!"resolve_external".equals(command.action())||!root.equals(original.rootId())
                ||!original.attemptId().equals(command.attemptId())||command.expectedRevision()<0||command.expectedRevision()>=CanonicalJson.MAX_SAFE_INTEGER
                ||command.expectedContentRevision()!=null||command.resultHash()!=null||command.externalState()==null||!Set.of("succeeded","failed","unknown").contains(command.externalState()))throw invalid();
        WireValues.id(command.attemptId(),false);text(command.reason(),2048);
        if(command.evidenceRefs()==null||command.evidenceRefs().isEmpty()||command.evidenceRefs().size()>100)throw invalid();
        var evidenceRefs=List.copyOf(command.evidenceRefs());
        var unique=new HashSet<String>();for(String ref:evidenceRefs)if(!unique.add(WireValues.id(ref,false)))throw invalid();
        if("succeeded".equals(command.externalState())!=(command.result()!=null))throw invalid();
        var value=JsonMapper.shared().createObjectNode().put("schema_version","owner-external-resolution-evidence-v1")
                .put("root_id",root).put("attempt_id",command.attemptId()).put("operator_id",actor.userId())
                .put("expected_revision",command.expectedRevision()).put("external_state",command.externalState()).put("reason",command.reason());
        var refs=value.putArray("evidence_refs");evidenceRefs.forEach(refs::add);
        Validated validated=null;
        if(command.result()==null)value.putNull("result");else{
            var raw=command.result();var envelope=JsonMapper.shared().createObjectNode().put("schema_version",raw.schemaVersion()).put("input_hash",raw.inputHash()).put("result_hash",raw.resultHash());
            if(raw.payload()==null)throw invalid();envelope.set("payload",raw.payload().deepCopy());
            try{validated=ResultApplicationService.validate(original,new String(CanonicalJson.bytes(envelope),StandardCharsets.UTF_8));}
            catch(ResultApplicationService.Rejected refused){throw invalid();}
            value.set("result",envelope);
        }
        if(command.usage()==null)value.putNull("usage");else{
            var u=command.usage();for(Long tokens:new Long[]{u.inputTokens(),u.outputTokens()})if(tokens!=null&&(tokens<0||tokens>CanonicalJson.MAX_SAFE_INTEGER))throw invalid();
            if(u.cost()!=null&&(u.cost().length()>30||!u.cost().matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?")))throw invalid();
            var usage=value.putObject("usage");if(u.inputTokens()==null)usage.putNull("input_tokens");else usage.put("input_tokens",u.inputTokens());
            if(u.outputTokens()==null)usage.putNull("output_tokens");else usage.put("output_tokens",u.outputTokens());
            if(u.cost()==null)usage.putNull("cost");else usage.put("cost",u.cost());
        }
        byte[] bytes=CanonicalJson.bytes(value);if(bytes.length>1048576)throw invalid();
        return new OwnerExternalResolutionEvidence(new String(bytes,StandardCharsets.UTF_8),root,command.attemptId(),actor.userId(),command.expectedRevision(),command.externalState(),validated);
    }
    public String canonical(){return canonical;}
    public String actionHash(){return actionHash;}
    public String rootId(){return rootId;}
    public String attemptId(){return attemptId;}
    public String operatorId(){return operatorId;}
    public long expectedRevision(){return expectedRevision;}
    public String externalState(){return externalState;}
    public Validated result(){return result;}
    @Override public String toString(){return "OwnerExternalResolutionEvidence[redacted]";}
    private static void text(String value,int limit){
        if(value==null||value.isBlank()||value.codePointCount(0,value.length())>limit)throw invalid();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid external verification material");}
}
