package com.lianxibu.site.controller.records;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.records.ArticleRecordReadService;
import com.lianxibu.site.service.records.RecordsService;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** Private article aggregate HTTP reader; existing record writes remain the sole mutation path. */
@RestController
public final class ArticleRecordsController {
    public record Article(String id,String title,String source_name) { }
    public record Item(Article article,RecordsDtos.Record record) { }
    public record Page(List<Item> items,long page,int page_size,long total,ArticleRecordReadService.Stats stats) { }
    private static final Set<String> STATUSES=Set.of("not_started","planned","learning","completed");
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<ArticleRecordReadService> records;
    public ArticleRecordsController(ObjectProvider<IdentityService> identity,ObjectProvider<ArticleRecordReadService> records){this.identity=identity;this.records=records;}

    @GetMapping(path="/private/article-records",produces="application/json")
    public ResponseEntity<RecordsDtos.Envelope<Page>> list(@CookieValue(name="lianxibu_session",required=false)String cookie,@RequestParam MultiValueMap<String,String> query){
        Actor actor=user(cookie);
        for(String field:query.keySet())if(!Set.of("q","status","favorite","page","page_size").contains(field)||query.get(field).size()!=1)throw new Failure(400,"protocol_error");
        String q=query.getFirst("q"),status=query.getFirst("status"),favorite=query.getFirst("favorite");
        if(q!=null&&(q.isEmpty()||q.codePointCount(0,q.length())>256)||status!=null&&!STATUSES.contains(status)||favorite!=null&&!Set.of("true","false").contains(favorite))throw new Failure(422,"validation");
        long page=positive(query.getFirst("page"),1,CanonicalJson.MAX_SAFE_INTEGER);int size=(int)positive(query.getFirst("page_size"),20,50);
        var service=records.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");
        var result=service.list(actor,q,status,favorite==null?null:Boolean.valueOf(favorite),page,size);
        if(result==null||result.items()==null||result.items().size()>size||result.page()!=page||result.pageSize()!=size||result.stats()==null)throw new Failure(503,"temporarily_unavailable");
        List<Item> items=result.items().stream().map(item->{var r=item.record();var a=item.article();if(r==null||!"article".equals(r.objectKind())||r.packageId()!=null||r.updatedAt()==null||r.revision()<0||r.revision()>CanonicalJson.MAX_SAFE_INTEGER||a!=null&&!a.id().equals(r.objectId()))throw new Failure(503,"temporarily_unavailable");return new Item(a==null?null:new Article(a.id(),a.title(),a.sourceName()),wire(r));}).toList();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new RecordsDtos.Envelope<>(new Page(items,page,size,result.total(),result.stats()),meta()));
    }
    private Actor user(String cookie){
        if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");
        if(actor.role()==null||!Set.of("owner","user").contains(actor.role()))throw new Failure(403,"forbidden");return actor;
    }
    private static long positive(String value,long fallback,long max){if(value==null)return fallback;try{if(value.matches("[1-9][0-9]*")){long number=Long.parseLong(value);if(number<=max)return number;}}catch(NumberFormatException ignored){}throw new Failure(422,"validation");}
    private static RecordsDtos.Record wire(RecordsService.Record r){return new RecordsDtos.Record(null,"article",r.objectId(),r.revision(),r.status(),r.selfAssessment(),r.note(),r.artifactUrl(),null,null,r.confirmedBodyRevision(),MICROS.format(r.updatedAt()),r.isRead(),r.isFavorite(),r.isIgnored());}
    @ExceptionHandler(Failure.class) ResponseEntity<RecordsDtos.ErrorEnvelope> failure(Failure error){return error(error.status,error.code);}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<RecordsDtos.ErrorEnvelope> dataFailure(DataAccessException ignored){return error(503,"temporarily_unavailable");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<RecordsDtos.ErrorEnvelope> invalid(IllegalArgumentException ignored){return error(422,"validation");}
    @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<RecordsDtos.ErrorEnvelope> rejected(IdentityService.Rejected error){int status=switch(error.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,error.refusal().name().toLowerCase(java.util.Locale.ROOT));}
    private static ResponseEntity<RecordsDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new RecordsDtos.ErrorEnvelope(new RecordsDtos.ErrorDetail(code,code),meta()));}
    private static RecordsDtos.Meta meta(){return new RecordsDtos.Meta(UUID.randomUUID().toString());}
    private static final class Failure extends RuntimeException{final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
