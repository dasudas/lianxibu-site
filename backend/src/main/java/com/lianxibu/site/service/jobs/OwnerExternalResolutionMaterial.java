package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcOwnerExternalAttemptReads;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.*;

/** Full original semantic proof, separate from current CAS and from external command persistence. */
public final class OwnerExternalResolutionMaterial {
    private final ResultApplicationService.Input input;
    private final FrozenApplicationContext context;
    private final OwnerExternalResolutionEvidence evidence;
    private OwnerExternalResolutionMaterial(ResultApplicationService.Input input,FrozenApplicationContext context,OwnerExternalResolutionEvidence evidence){this.input=input;this.context=context;this.evidence=evidence;}
    public static OwnerExternalResolutionMaterial verify(Actor actor,String root,JobsService.JobAction command,JdbcOwnerExternalAttemptReads.Frame frame){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        final FrozenApplicationContext context;final ResultApplicationService.Input input;
        try{
            Objects.requireNonNull(frame);var nativeRoot=Objects.requireNonNull(frame.root());var identity=Objects.requireNonNull(frame.identity());
            var body=CanonicalJson.parse(frame.inputJson());
            if(!root.equals(nativeRoot.id())||!"logical_task".equals(nativeRoot.kind())||!root.equals(identity.rootId())
                    ||frame.originalRootRevision()<0||frame.originalRootRevision()>nativeRoot.revision()||frame.generation()<1
                    ||!identity.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,body))
                    ||!"1".equals(WireValues.text(body.get("schema_version")))||!nativeRoot.taskType().equals(WireValues.text(body.get("stage")))
                    ||frame.generation()!=WireValues.nonnegativeInteger(body.get("generation"))||!Objects.equals(nativeRoot.cutoff(),WireValues.utcMicroseconds(body.get("cutoff")))
                    ||!Set.of("complete","partial","no_input").contains(nativeRoot.coverageState())||!nativeRoot.coverageState().equals(WireValues.text(body.get("coverage_state")))
                    ||nativeRoot.missingJson()==null||!CanonicalJson.parse(nativeRoot.missingJson()).equals(body.get("missing")))throw unavailable();
            WireValues.businessDate(body.get("business_date"));WireValues.sha256(body.get("settings_hash"));
            var missing=body.get("missing");if(missing==null||!missing.isArray()||missing.size()>1000)throw unavailable();var seen=new HashSet<String>();
            for(var value:missing){String text=WireValues.text(value);if(text.isBlank()||text.codePointCount(0,text.length())>256||text.codePoints().anyMatch(Character::isISOControl)||!seen.add(text))throw unavailable();}
            if("complete".equals(nativeRoot.coverageState())&&!missing.isEmpty())throw unavailable();
            context=FrozenApplicationContext.fromJson(body.get(FrozenApplicationContext.FIELD));
            if(!context.stage().name().equalsIgnoreCase(nativeRoot.taskType())||context.generation()!=frame.generation())throw unavailable();
            if(context.stage().analysis()||context.stage().report())FrozenPublicMetadata.read(body.get(FrozenPublicMetadata.FIELD),context);
            if(context.stage().report()){
                ReportInputProfile.validated(body);var profile=body.get(ReportInputProfile.FIELD);
                if(!context.periodStart().equals(WireValues.text(profile.get("period_start")))||!context.periodEnd().equals(WireValues.text(profile.get("period_end"))))throw unavailable();
            }
            input=context.input(root,identity.attemptId(),identity.inputHash(),frame.originalRootRevision());
        }catch(RuntimeException corrupt){throw unavailable();}
        // Client errors retain their parameter category; original native corruption above is storage failure.
        return new OwnerExternalResolutionMaterial(input,context,OwnerExternalResolutionEvidence.verify(actor,root,command,input));
    }
    public ResultApplicationService.Input input(){return input;}
    public FrozenApplicationContext context(){return context;}
    public OwnerExternalResolutionEvidence evidence(){return evidence;}
    @Override public String toString(){return "OwnerExternalResolutionMaterial[redacted]";}
    private static IllegalStateException unavailable(){return new IllegalStateException("original external input semantics unavailable");}
}
