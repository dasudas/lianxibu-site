package com.lianxibu.site.controller.learning;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.learning.OwnerPackageReadService;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
public final class OwnerPackageController {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final ObjectProvider<IdentityService> identity;private final ObjectProvider<OwnerPackageReadService> reads;
 public OwnerPackageController(ObjectProvider<IdentityService> identity,ObjectProvider<OwnerPackageReadService> reads){this.identity=identity;this.reads=reads;}
 @GetMapping(path="/admin/learning/packages/{id}",produces="application/json")
 public ResponseEntity<byte[]> read(@CookieValue(name="lianxibu_session",required=false)String cookie,@PathVariable("id")String packageId,@RequestParam MultiValueMap<String,String> query){
  var actor=owner(cookie);if(!query.isEmpty())throw new Failure(400,"protocol_error");WireValues.id(packageId,false);
  var service=reads.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");var data=service.read(actor,packageId);OwnerPackageReadService.validateProjection(data,packageId);
  byte[] bytes=JSON.writeValueAsBytes(new LearningDtos.Envelope<>(data,meta()));if(bytes.length>1024*1024)throw new Failure(503,"temporarily_unavailable");return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type","application/json").body(bytes);
 }
 private IdentityService.Actor owner(String cookie){if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;}
 @ExceptionHandler(Failure.class) ResponseEntity<LearningDtos.ErrorEnvelope> failed(Failure value){return error(value.status,value.code);}
 @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<LearningDtos.ErrorEnvelope> unavailable(Exception ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<LearningDtos.ErrorEnvelope> invalid(Exception ignored){return error(422,"validation");}
 @ExceptionHandler(OwnerPackageReadService.Missing.class) ResponseEntity<LearningDtos.ErrorEnvelope> missing(Exception ignored){return error(404,"not_found");}
 @ExceptionHandler(OwnerPackageReadService.Denied.class) ResponseEntity<LearningDtos.ErrorEnvelope> denied(Exception ignored){return error(403,"forbidden");}
 @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<LearningDtos.ErrorEnvelope> authRejected(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(Locale.ROOT));}
 private static ResponseEntity<LearningDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new LearningDtos.ErrorEnvelope(new LearningDtos.ErrorDetail(code,code),meta()));}
 private static LearningDtos.Meta meta(){return new LearningDtos.Meta(UUID.randomUUID().toString());}
 private static final class Failure extends RuntimeException {final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
