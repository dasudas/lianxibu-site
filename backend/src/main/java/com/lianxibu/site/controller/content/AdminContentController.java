package com.lianxibu.site.controller.content;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.AdminContentReadService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
/** Owner metadata directory. Existing single-content command routes stay authoritative. */
@RestController
public final class AdminContentController {
 public record Item(String id,String kind,String title,long revision,Long body_revision,String publication_state,String report_type) { }
 private static final Set<String> KINDS=Set.of("report","model","agent","skill","workflow"),STATES=Set.of("draft","approved","published","withdrawn"),TYPES=Set.of("daily","weekly","monthly");
 private final ObjectProvider<IdentityService> identity;private final ObjectProvider<AdminContentReadService> content;
 public AdminContentController(ObjectProvider<IdentityService> identity,ObjectProvider<AdminContentReadService> content){this.identity=identity;this.content=content;}
 @GetMapping(path="/admin/content",produces="application/json")
 public ResponseEntity<ContentDtos.Envelope<ContentDtos.Page<Item>>> list(@CookieValue(name="lianxibu_session",required=false)String cookie,@RequestParam MultiValueMap<String,String> query){
  Actor actor=owner(cookie);for(String key:query.keySet())if(!Set.of("kind","state","type","page","page_size").contains(key)||query.get(key).size()!=1)throw new Failure(400,"protocol_error");
  String kind=query.getFirst("kind"),state=query.getFirst("state"),type=query.getFirst("type");
  if(kind!=null&&!KINDS.contains(kind)||state!=null&&!STATES.contains(state)||type!=null&&(!TYPES.contains(type)||kind!=null&&!kind.equals("report")))throw new Failure(422,"validation");
  long page=positive(query.getFirst("page"),1,CanonicalJson.MAX_SAFE_INTEGER);int size=(int)positive(query.getFirst("page_size"),20,50);
  var service=content.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");var result=service.list(actor,kind,state,type,page,size);
  if(result==null||result.items()==null||result.page()!=page||result.pageSize()!=size||result.items().size()>size||result.total()<result.items().size()||result.total()>CanonicalJson.MAX_SAFE_INTEGER)throw new Failure(503,"temporarily_unavailable");
  var items=result.items().stream().map(row->new Item(row.id(),row.kind(),row.title(),row.revision(),row.bodyRevision(),row.publicationState(),row.reportType())).toList();
  return ResponseEntity.ok().header("Cache-Control","no-store").body(new ContentDtos.Envelope<>(new ContentDtos.Page<>(items,page,size,result.total()),meta()));
 }
 private Actor owner(String cookie){if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;}
 private static long positive(String value,long fallback,long max){if(value==null)return fallback;try{if(value.matches("[1-9][0-9]*")){long number=Long.parseLong(value);if(number<=max)return number;}}catch(NumberFormatException ignored){}throw new Failure(422,"validation");}
 @ExceptionHandler(Failure.class) ResponseEntity<ContentDtos.ErrorEnvelope> failure(Failure error){return error(error.status,error.code);}
 @ExceptionHandler(DataAccessException.class) ResponseEntity<ContentDtos.ErrorEnvelope> dataFailure(DataAccessException ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalStateException.class) ResponseEntity<ContentDtos.ErrorEnvelope> invalidStorage(IllegalStateException ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ContentDtos.ErrorEnvelope> invalid(IllegalArgumentException ignored){return error(422,"validation");}
 @ExceptionHandler(AdminContentReadService.Denied.class) ResponseEntity<ContentDtos.ErrorEnvelope> denied(AdminContentReadService.Denied ignored){return error(403,"forbidden");}
 @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<ContentDtos.ErrorEnvelope> rejected(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(java.util.Locale.ROOT));}
 private static ResponseEntity<ContentDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new ContentDtos.ErrorEnvelope(new ContentDtos.ErrorDetail(code,code),meta()));}
 private static ContentDtos.Meta meta(){return new ContentDtos.Meta(UUID.randomUUID().toString());}
 private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
