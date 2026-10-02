package com.lianxibu.site.controller.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.JobScheduleReadService;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** Separate additive scheduling read; owner authorization precedes all proof reads. */
@RestController
public final class JobScheduleController {
    public record Period(String period_start,String period_end,String timezone){}
    public record Schedule(String job_id,long job_revision,Period planned_period,String first_queued_at,String observed_at){}
    private final ObjectProvider<IdentityService> identity;private final ObjectProvider<JobScheduleReadService> schedules;
    public JobScheduleController(ObjectProvider<IdentityService> identity,ObjectProvider<JobScheduleReadService> schedules){this.identity=identity;this.schedules=schedules;}
    @GetMapping(path="/admin/jobs/{id}/schedule",produces="application/json")
    public ResponseEntity<JobsDtos.Envelope<Schedule>> read(@CookieValue(name="lianxibu_session",required=false)String cookie,@PathVariable("id")String id,@RequestParam MultiValueMap<String,String> query){
        if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");
        if(!query.isEmpty())throw new Failure(400,"protocol_error");try{WireValues.id(id,false);}catch(IllegalArgumentException error){throw new Failure(422,"validation");}
        var service=schedules.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");JobScheduleReadService.Schedule value;
        try{value=service.read(id).orElse(null);}catch(RuntimeException error){throw new Failure(503,"temporarily_unavailable");}
        if(value==null)throw new Failure(404,"not_found");
        try{
            if(!id.equals(value.jobId())||value.jobRevision()<0||value.jobRevision()>CanonicalJson.MAX_SAFE_INTEGER||value.observedAt()==null||value.firstQueuedAt()!=null&&value.firstQueuedAt().isAfter(value.observedAt()))throw new IllegalArgumentException();
            var p=value.period();if(p!=null&&(!"Asia/Shanghai".equals(p.timezone())||p.start()==null||p.end()==null||!p.end().isAfter(p.start())))throw new IllegalArgumentException();
            var wire=new Schedule(id,value.jobRevision(),p==null?null:new Period(time(p.start()),time(p.end()),p.timezone()),time(value.firstQueuedAt()),time(value.observedAt()));
            return ResponseEntity.ok().header("Cache-Control","no-store").body(new JobsDtos.Envelope<>(wire,new JobsDtos.Meta(UUID.randomUUID().toString())));
        }catch(RuntimeException error){throw new Failure(503,"temporarily_unavailable");}
    }
    @ExceptionHandler(Failure.class)
    public ResponseEntity<JobsDtos.ErrorEnvelope> failed(Failure error){var response=ResponseEntity.status(error.status).header("Cache-Control","no-store");if(error.status==503||error.status==429)response.header("Retry-After","1");return response.body(new JobsDtos.ErrorEnvelope(new JobsDtos.ErrorDetail(error.code,error.code),new JobsDtos.Meta(UUID.randomUUID().toString())));}
    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<JobsDtos.ErrorEnvelope> identityRejected(IdentityService.Rejected error){int status=switch(error.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return failed(new Failure(status,error.refusal().name().toLowerCase(java.util.Locale.ROOT)));}
    private static String time(Instant value){if(value==null)return null;if(value.getNano()%1000!=0)throw new IllegalArgumentException();return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){super(code);this.status=status;this.code=code;}}
}
