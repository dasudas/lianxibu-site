package com.lianxibu.site.controller.content;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.content.OperationAffectedReadService;
import com.lianxibu.site.service.identity.IdentityService;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
/** Owner-only impact proof projection. Missing runtime assembly cannot invent an empty result. */
@RestController
public final class OperationAffectedController {
 public record Ref(String kind,String id,String package_id) { }
 public record Page(String operation_ref,String state,Long total,List<Ref> items,String next_cursor) { }
 private final ObjectProvider<IdentityService> identity;private final ObjectProvider<OperationAffectedReadService> reads;
 public OperationAffectedController(ObjectProvider<IdentityService> identity,ObjectProvider<OperationAffectedReadService> reads){this.identity=identity;this.reads=reads;}
 @GetMapping(path="/admin/operations/{id}/affected",produces="application/json")
 public ResponseEntity<ContentDtos.Envelope<Page>> read(@CookieValue(name="lianxibu_session",required=false)String cookie,@PathVariable("id")String id,@RequestParam MultiValueMap<String,String> query){
  var actor=owner(cookie);for(String field:query.keySet())if(!Set.of("cursor","limit").contains(field)||query.get(field).size()!=1)throw new Failure(400,"protocol_error");WireValues.id(id,false);
  int limit=20;String raw=query.getFirst("limit");if(raw!=null){try{if(!raw.matches("[1-9][0-9]*"))throw new IllegalArgumentException();limit=Integer.parseInt(raw);}catch(IllegalArgumentException ignored){throw new Failure(422,"validation");}if(limit>50)throw new Failure(422,"validation");}
  String cursor=query.getFirst("cursor");if(cursor!=null&&(cursor.isEmpty()||cursor.codePointCount(0,cursor.length())>512))throw new Failure(422,"validation");var service=reads.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");var page=service.read(actor,id,cursor,limit);
  if(page==null||!id.equals(page.operationRef())||page.items()==null||page.items().size()>limit||page.state()==null||!Set.of("pending","complete").contains(page.state()))throw new Failure(503,"temporarily_unavailable");
  if(page.state().equals("pending")?(page.total()!=null||!page.items().isEmpty()||page.nextCursor()!=null):(page.total()==null||page.total()<page.items().size()||page.total()>0&&page.items().isEmpty()||page.total()>com.lianxibu.site.domain.protocol.CanonicalJson.MAX_SAFE_INTEGER||page.nextCursor()!=null&&(page.items().size()!=limit||page.total()<=page.items().size()||page.nextCursor().isEmpty()||page.nextCursor().length()>512)))throw new Failure(503,"temporarily_unavailable");
  var seen=new HashSet<OperationAffectedReadService.Ref>();var items=page.items().stream().map(ref->{try{if(ref==null||ref.kind()==null||ref.kind().isEmpty()||ref.kind().codePointCount(0,ref.kind().length())>32||ref.kind().codePoints().anyMatch(Character::isISOControl)||!seen.add(ref))throw new IllegalArgumentException();WireValues.id(ref.id(),false);if(ref.packageId()!=null)WireValues.id(ref.packageId(),false);}catch(IllegalArgumentException ignored){throw new Failure(503,"temporarily_unavailable");}return new Ref(ref.kind(),ref.id(),ref.packageId());}).toList();
  return ResponseEntity.ok().header("Cache-Control","no-store").body(new ContentDtos.Envelope<>(new Page(id,page.state(),page.total(),items,page.nextCursor()),meta()));
 }
 private IdentityService.Actor owner(String cookie){if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;}
 @ExceptionHandler(Failure.class) ResponseEntity<ContentDtos.ErrorEnvelope> failed(Failure value){return error(value.status,value.code);}
 @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<ContentDtos.ErrorEnvelope> unavailable(Exception ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ContentDtos.ErrorEnvelope> invalid(Exception ignored){return error(422,"validation");}
 @ExceptionHandler(OperationAffectedReadService.Missing.class) ResponseEntity<ContentDtos.ErrorEnvelope> missing(Exception ignored){return error(404,"not_found");}
 @ExceptionHandler(OperationAffectedReadService.Denied.class) ResponseEntity<ContentDtos.ErrorEnvelope> denied(Exception ignored){return error(403,"forbidden");}
 @ExceptionHandler(OperationAffectedReadService.CursorConflict.class) ResponseEntity<ContentDtos.ErrorEnvelope> conflict(Exception ignored){return error(409,"cursor_scope_conflict");}
 @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<ContentDtos.ErrorEnvelope> authRejected(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(Locale.ROOT));}
 private static ResponseEntity<ContentDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new ContentDtos.ErrorEnvelope(new ContentDtos.ErrorDetail(code,code),meta()));}
 private static ContentDtos.Meta meta(){return new ContentDtos.Meta(UUID.randomUUID().toString());}
 private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
