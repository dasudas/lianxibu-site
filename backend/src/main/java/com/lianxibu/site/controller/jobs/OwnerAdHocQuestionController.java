package com.lianxibu.site.controller.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.OwnerAdHocQuestionReadService;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Owner-only original temporary question and proven retained answer. */
@RestController
public final class OwnerAdHocQuestionController {
 private final ObjectProvider<IdentityService> identity;
 private final ObjectProvider<OwnerAdHocQuestionReadService> reads;
 public OwnerAdHocQuestionController(ObjectProvider<IdentityService> identity,ObjectProvider<OwnerAdHocQuestionReadService> reads){this.identity=Objects.requireNonNull(identity);this.reads=Objects.requireNonNull(reads);}
 @GetMapping(path="/admin/jobs/{id}/ad-hoc",produces="application/json")
 public ResponseEntity<JobsDtos.Envelope<Map<String,Object>>> get(@CookieValue(name="lianxibu_session",required=false) String cookie,@PathVariable("id") String id,@RequestParam MultiValueMap<String,String> query){
  var actor=owner(cookie);if(!query.isEmpty())throw new Failure(400,"protocol_error");WireValues.id(id,false);
  var service=reads.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");
  try{
   var view=service.get(actor,id);if(view==null||view.intent()==null||!id.equals(view.intent().id())||view.originalRequest()==null)throw new Failure(503,"temporarily_unavailable");
   var data=map("intent",JobsDtos.job(view.intent()),"original_request",map("prompt",view.originalRequest().prompt()),"prepared_job",view.preparedJob()==null?null:JobsDtos.job(view.preparedJob()),"retained_result",null);
   var result=view.retainedResult();if(result!=null)data.put("retained_result",map("schema_version",result.schemaVersion(),"input_hash",result.inputHash(),"payload",result.payload(),"result_hash",result.resultHash()));
   var envelope=new JobsDtos.Envelope<>(data,meta());if(CanonicalJson.bytes(JsonMapper.shared().valueToTree(envelope)).length>1048576)throw new Failure(503,"temporarily_unavailable");
   return ResponseEntity.ok().header("Cache-Control","no-store").body(envelope);
  }catch(IdentityService.Rejected refused){throw refused;}catch(RuntimeException failed){throw new Failure(503,"temporarily_unavailable");}
 }
 private IdentityService.Actor owner(String cookie){if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;}
 @ExceptionHandler(Failure.class) ResponseEntity<JobsDtos.ErrorEnvelope> failure(Failure error){return error(error.status,error.code);}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JobsDtos.ErrorEnvelope> invalid(Exception ignored){return error(422,"validation");}
 @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<JobsDtos.ErrorEnvelope> refused(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(Locale.ROOT));}
 private static ResponseEntity<JobsDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==503||status==429)response.header("Retry-After","1");return response.body(new JobsDtos.ErrorEnvelope(new JobsDtos.ErrorDetail(code,code),meta()));}
 private static JobsDtos.Meta meta(){return new JobsDtos.Meta(UUID.randomUUID().toString());}
 private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
 private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
