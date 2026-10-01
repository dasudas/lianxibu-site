package com.lianxibu.site.controller.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.JobResultComparisonReadService;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Closed owner-only projection; absent permanent runtime assembly fails closed. */
@RestController
public final class JobResultComparisonController {
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<JobResultComparisonReadService> reads;
    public JobResultComparisonController(ObjectProvider<IdentityService> identity,ObjectProvider<JobResultComparisonReadService> reads){this.identity=identity;this.reads=reads;}
    @GetMapping(path="/admin/jobs/{id}/result-comparison",produces="application/json")
    public ResponseEntity<JobsDtos.Envelope<Map<String,Object>>> read(
            @CookieValue(name="lianxibu_session",required=false) String cookie,
            @PathVariable("id") String id,@RequestParam MultiValueMap<String,String> query){
        var actor=owner(cookie);
        if(!query.isEmpty())throw new Failure(400,"protocol_error");
        WireValues.id(id,false);
        var service=reads.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");
        var view=service.read(actor,id);
        if(view==null||!id.equals(view.jobId())||view.jobRevision()<0||view.jobRevision()>CanonicalJson.MAX_SAFE_INTEGER)throw new Failure(503,"temporarily_unavailable");
        Map<String,Object> data=map("job_id",view.jobId(),"job_revision",view.jobRevision());
        if(view instanceof JobResultComparisonReadService.Unavailable value){
            data.putAll(map("state","unavailable","reason",value.reason()));
        }else if(view instanceof JobResultComparisonReadService.Comparison value){
            var coverage=value.coverage();
            data.putAll(map("state","comparison","content_kind",value.contentKind(),"content_id",value.contentId(),
                    "current_content_revision",value.currentContentRevision(),"current_body_revision",value.currentBodyRevision(),
                    "current_body",value.currentBody(),"candidate_body",value.candidateBody(),"result_hash",value.resultHash(),
                    "input_snapshot_id",value.inputSnapshotId(),"generation",value.generation(),"input_cutoff_at",time(value.cutoff()),
                    "coverage",map("state",coverage.state(),"missing_inputs",coverage.missingInputs().stream().map(m->map("kind",m.kind(),"id",m.id(),"business_date",m.businessDate()==null?null:m.businessDate().toString(),"reason",m.reason())).toList(),
                            "missing_dates",coverage.missingDates().stream().map(Object::toString).toList(),"no_update_dates",coverage.noUpdateDates().stream().map(Object::toString).toList()),
                    "can_apply",value.canApply(),"blocking_reasons",value.blockingReasons()));
        }else throw new Failure(503,"temporarily_unavailable");
        var envelope=new JobsDtos.Envelope<>(data,meta());
        if(CanonicalJson.bytes(JSON.valueToTree(envelope)).length>1048576)throw new Failure(503,"temporarily_unavailable");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(envelope);
    }
    private IdentityService.Actor owner(String cookie){
        if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");
        var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");
        var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");
        if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;
    }
    @ExceptionHandler(Failure.class) ResponseEntity<JobsDtos.ErrorEnvelope> failed(Failure value){return error(value.status,value.code);}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<JobsDtos.ErrorEnvelope> unavailable(Exception ignored){return error(503,"temporarily_unavailable");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JobsDtos.ErrorEnvelope> invalid(Exception ignored){return error(422,"validation");}
    @ExceptionHandler(JobResultComparisonReadService.Missing.class) ResponseEntity<JobsDtos.ErrorEnvelope> missing(Exception ignored){return error(404,"not_found");}
    @ExceptionHandler(JobResultComparisonReadService.Denied.class) ResponseEntity<JobsDtos.ErrorEnvelope> denied(Exception ignored){return error(403,"forbidden");}
    @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<JobsDtos.ErrorEnvelope> authRejected(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(Locale.ROOT));}
    private static ResponseEntity<JobsDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new JobsDtos.ErrorEnvelope(new JobsDtos.ErrorDetail(code,code),meta()));}
    private static JobsDtos.Meta meta(){return new JobsDtos.Meta(UUID.randomUUID().toString());}
    private static String time(Instant value){return value==null?null:MICROS.format(value);}
    private static Map<String,Object> map(Object...pairs){var value=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
