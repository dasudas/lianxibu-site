package com.lianxibu.site.controller.content;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.VersionDispositionReadService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
/** Exact-version active invalidations only; no raw disposition payload or history body. */
@RestController
public final class VersionDispositionController {
 public record Target(String kind,String content_kind,String content_id,long revision,String body_hash,String input_job_id) { }
 public record Item(String id,String reason,String created_at) { }
 public record Page(Target target,long validity_revision,boolean effective_valid,java.util.List<Item> items,long page,int page_size,long total) { }
 private static final Set<String> KINDS=Set.of("report","model","agent","skill","workflow");
 private final ObjectProvider<IdentityService> identity;private final ObjectProvider<VersionDispositionReadService> content;
 public VersionDispositionController(ObjectProvider<IdentityService> identity,ObjectProvider<VersionDispositionReadService> content){this.identity=identity;this.content=content;}
 @GetMapping(path="/admin/content/{kind}/{id}/version-dispositions",produces="application/json")
 public ResponseEntity<ContentDtos.Envelope<Page>> list(@CookieValue(name="lianxibu_session",required=false)String cookie,@PathVariable("kind")String kind,@PathVariable("id")String id,@RequestParam MultiValueMap<String,String> query){
  Actor actor=owner(cookie);for(String key:query.keySet())if(!Set.of("revision","body_hash","input_job_id","page","page_size").contains(key)||query.get(key).size()!=1)throw new Failure(400,"protocol_error");
  if(!KINDS.contains(kind))throw new Failure(422,"validation");com.lianxibu.site.domain.protocol.WireValues.id(id,false);
  long revision=nonnegative(query.getFirst("revision"));String hash=query.getFirst("body_hash"),input=query.getFirst("input_job_id");if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new Failure(422,"validation");if(input!=null)com.lianxibu.site.domain.protocol.WireValues.id(input,false);
  long page=positive(query.getFirst("page"),1,CanonicalJson.MAX_SAFE_INTEGER);int size=(int)positive(query.getFirst("page_size"),20,50);
  var service=content.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");var result=service.list(actor,kind,id,revision,hash,input,page,size);
  var expected=new com.lianxibu.site.domain.protocol.ContentVersionKey(kind,id,revision,hash,input);
  if(result==null||!expected.equals(result.target())||result.items()==null||result.page()!=page||result.pageSize()!=size||result.items().size()>size||result.total()<0||result.total()>CanonicalJson.MAX_SAFE_INTEGER||result.validityRevision()<0||result.validityRevision()>CanonicalJson.MAX_SAFE_INTEGER||result.items().size()>Math.max(0,result.total()-(page-1)*size)||result.effectiveValid()!=(result.total()==0))throw new Failure(503,"temporarily_unavailable");
  var seen=new java.util.HashSet<String>();var items=result.items().stream().map(row->{if(row==null||row.id()==null||row.reason()==null||row.reason().isBlank()||row.reason().codePointCount(0,row.reason().length())>2048||row.createdAt()==null)throw new Failure(503,"temporarily_unavailable");try{com.lianxibu.site.domain.protocol.WireValues.id(row.id(),false);}catch(IllegalArgumentException ignored){throw new Failure(503,"temporarily_unavailable");}if(!seen.add(row.id()))throw new Failure(503,"temporarily_unavailable");String time=new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(row.createdAt());if(!time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z"))throw new Failure(503,"temporarily_unavailable");return new Item(row.id(),row.reason(),time);}).toList();
  return ResponseEntity.ok().header("Cache-Control","no-store").body(new ContentDtos.Envelope<>(new Page(new Target("content_version",kind,id,revision,hash,input),result.validityRevision(),result.effectiveValid(),items,page,size,result.total()),meta()));
 }
 private static long nonnegative(String value){try{if(value!=null&&value.matches("0|[1-9][0-9]*")){long number=Long.parseLong(value);if(number<=CanonicalJson.MAX_SAFE_INTEGER)return number;}}catch(NumberFormatException ignored){}throw new Failure(422,"validation");}
 private Actor owner(String cookie){if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;}
 private static long positive(String value,long fallback,long max){if(value==null)return fallback;try{if(value.matches("[1-9][0-9]*")){long number=Long.parseLong(value);if(number<=max)return number;}}catch(NumberFormatException ignored){}throw new Failure(422,"validation");}
 @ExceptionHandler(Failure.class) ResponseEntity<ContentDtos.ErrorEnvelope> failure(Failure error){return error(error.status,error.code);}
 @ExceptionHandler(DataAccessException.class) ResponseEntity<ContentDtos.ErrorEnvelope> dataFailure(DataAccessException ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalStateException.class) ResponseEntity<ContentDtos.ErrorEnvelope> invalidStorage(IllegalStateException ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ContentDtos.ErrorEnvelope> invalid(IllegalArgumentException ignored){return error(422,"validation");}
 @ExceptionHandler(VersionDispositionReadService.Missing.class) ResponseEntity<ContentDtos.ErrorEnvelope> missing(VersionDispositionReadService.Missing ignored){return error(404,"not_found");}
 @ExceptionHandler(VersionDispositionReadService.Denied.class) ResponseEntity<ContentDtos.ErrorEnvelope> denied(VersionDispositionReadService.Denied ignored){return error(403,"forbidden");}
 @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<ContentDtos.ErrorEnvelope> rejected(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(java.util.Locale.ROOT));}
 private static ResponseEntity<ContentDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new ContentDtos.ErrorEnvelope(new ContentDtos.ErrorDetail(code,code),meta()));}
 private static ContentDtos.Meta meta(){return new ContentDtos.Meta(UUID.randomUUID().toString());}
 private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
