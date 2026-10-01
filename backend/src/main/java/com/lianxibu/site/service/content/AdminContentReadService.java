package com.lianxibu.site.service.content;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.JdbcAdminContentReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
/** Owner-only safe metadata, preserving unknown legacy period types and body versions. */
public final class AdminContentReadService {
 public record Item(String id,String kind,String title,long revision,Long bodyRevision,String publicationState,String reportType) { }
 public record Page(List<Item> items,long page,int pageSize,long total) { }
 public static final class Denied extends RuntimeException { }
 private static final Set<String> KINDS=Set.of("report","model","agent","skill","workflow"),STATES=Set.of("draft","approved","published","withdrawn"),TYPES=Set.of("daily","weekly","monthly");
 private final JdbcAdminContentReads store;private final TransactionTemplate reads;
 public AdminContentReadService(JdbcAdminContentReads store,PlatformTransactionManager tx){if(store==null||tx==null)throw new IllegalArgumentException("read dependencies required");this.store=store;reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 public Page list(Actor actor,String kind,String state,String type,long page,int size){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
  if(kind!=null&&!KINDS.contains(kind)||state!=null&&!STATES.contains(state)||type!=null&&(!TYPES.contains(type)||kind!=null&&!kind.equals("report"))||page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid content filters");
  final String contentKind=type!=null?"report":kind;
  return reads.execute(status->{long offset=(page-1)*size,total=0;var items=new ArrayList<Item>();String afterKind="",afterId="";
   while(true){var batch=store.scan(contentKind,state,afterKind,afterId,500);if(batch.isEmpty())break;
    for(var row:batch){validate(row);String reportType=period(row.kind(),row.reportJson());if(type!=null&&!type.equals(reportType))continue;if(total>=offset&&items.size()<size)items.add(new Item(row.id(),row.kind(),row.title(),row.revision(),row.bodyRevision(),row.state(),reportType));if(total==CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalStateException("count unavailable");total++;}
    var last=batch.getLast();afterKind=last.kind();afterId=last.id();if(batch.size()<500)break;
   }return new Page(List.copyOf(items),page,size,total);
  });
 }
 private static String period(String kind,String raw){if(!"report".equals(kind)||raw==null)return null;try{var body=CanonicalJson.parse(raw);var type=body.isObject()?body.get("type"):null;return type!=null&&type.isString()&&TYPES.contains(type.asString())?type.asString():null;}catch(IllegalArgumentException ignored){return null;}}
 private static void validate(JdbcAdminContentReads.Row row){if(!KINDS.contains(row.kind())||!STATES.contains(row.state())||row.id()==null||row.id().isEmpty()||row.id().codePointCount(0,row.id().length())>64||row.id().chars().anyMatch(c->c<32||c==127)||row.title()==null||row.title().isEmpty()||row.title().codePointCount(0,row.title().length())>512||row.revision()<0||row.revision()>CanonicalJson.MAX_SAFE_INTEGER||row.bodyRevision()!=null&&(row.bodyRevision()<0||row.bodyRevision()>CanonicalJson.MAX_SAFE_INTEGER))throw new IllegalStateException("content metadata unavailable");}
}
