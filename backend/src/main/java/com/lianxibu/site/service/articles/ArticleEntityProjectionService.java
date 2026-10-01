package com.lianxibu.site.service.articles;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.articles.JdbcArticleEntityReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
/** Decorates the base facade only when an opt-in summary is requested. Runtime assembly is separate. */
public final class ArticleEntityProjectionService implements ArticlesService {
 public static final class Denied extends RuntimeException { }
 private final ArticlesService base;private final JdbcArticleEntityReads store;private final TransactionTemplate reads;
 public ArticleEntityProjectionService(ArticlesService base,JdbcArticleEntityReads store,PlatformTransactionManager tx){if(base==null||store==null||tx==null)throw new IllegalArgumentException("projection dependencies required");this.base=base;this.store=store;reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 @Override public ArticleWithEntity articleWithEntity(Actor actor,String id){authorize(actor);WireValues.id(id,false);return reads.execute(status->{var article=base.article(actor,id);if(article==null||!id.equals(article.id()))throw unavailable();return enrich(article);});}
 @Override public ArticleEntityPage listArticlesWithEntity(Actor actor,String sourceId,String query,String status,long page,int size){authorize(actor);if(page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid page");if(sourceId!=null)WireValues.id(sourceId,true);return reads.execute(tx->{var result=base.listArticles(actor,sourceId,query,status,page,size);if(result==null||result.items()==null||result.page()!=page||result.pageSize()!=size||result.items().size()>size||result.total()<0||result.total()>CanonicalJson.MAX_SAFE_INTEGER||result.items().size()>Math.max(0,result.total()-(page-1)*size))throw unavailable();var items=new ArrayList<ArticleWithEntity>();var seen=new java.util.HashSet<String>();for(var article:result.items()){if(article==null||!seen.add(article.id())||sourceId!=null&&!sourceId.equals(article.sourceId()))throw unavailable();items.add(enrich(article));}return new ArticleEntityPage(List.copyOf(items),page,size,result.total());});}
 private ArticleWithEntity enrich(Article article){try{WireValues.id(article.id(),false);WireValues.id(article.sourceId(),true);String entityId=null,after=null;boolean uncertain=false;while(true){var batch=store.links(article.id(),after);for(var link:batch){if(!article.id().equals(link.articleId())||!article.sourceId().equals(link.sourceId())||!"news_tech".equals(link.category())||!Set.of("ready","analyzed").contains(link.state()==null?"":link.state())||link.entityId()==null){uncertain=true;continue;}WireValues.id(link.entityId(),false);if(entityId==null)entityId=link.entityId();else if(!entityId.equals(link.entityId()))uncertain=true;}if(batch.size()<500)break;after=batch.getLast().id();WireValues.id(after,false);}
  if(uncertain||entityId==null)return new ArticleWithEntity(article,null);var entity=store.entity(entityId);if(entity==null||!entityId.equals(entity.id())||!"news_tech".equals(entity.category()))return new ArticleWithEntity(article,null);
  return new ArticleWithEntity(article,summary(entity));
 }catch(IllegalArgumentException error){throw unavailable();}}
 private static EntitySummary summary(JdbcArticleEntityReads.Entity value){if(value.revision()<0||value.revision()>CanonicalJson.MAX_SAFE_INTEGER||(value.occurrenceCount()==null)!=(value.repeatCount()==null)||value.firstRecordedAt()!=null&&value.lastSeenAt()!=null&&value.firstRecordedAt().isAfter(value.lastSeenAt()))throw unavailable();if(value.occurrenceCount()!=null&&(value.occurrenceCount()<0||value.occurrenceCount()>CanonicalJson.MAX_SAFE_INTEGER||value.repeatCount()!=Math.max(value.occurrenceCount()-1,0)))throw unavailable();String state=value.analysisState();if(state==null||!Set.of("pending","queued","analyzed","failed","needs_review").contains(state))state=null;return new EntitySummary(value.id(),"news_tech",value.firstRecordedAt(),value.firstSeenBusinessDate(),value.lastSeenAt(),value.occurrenceCount(),value.repeatCount(),state,value.revision());}
 private static void authorize(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank()||!("owner".equals(actor.role())||"user".equals(actor.role())))throw new Denied();}
 private static IllegalStateException unavailable(){return new IllegalStateException("article entity proof unavailable");}
 @Override public ArticlePage listArticles(Actor actor,String sourceId,String query,String status,long page,int size){return base.listArticles(actor,sourceId,query,status,page,size);}
 @Override public Article article(Actor actor,String id){return base.article(actor,id);}
 @Override public EntityPage listEntities(Actor actor,String category,String query,long page,int size){return base.listEntities(actor,category,query,page,size);}
 @Override public Entity entity(Actor actor,String id){return base.entity(actor,id);}
 @Override public ActionResult actOnEntity(Actor actor,String id,EntityAction command,String key,String csrf,String origin){return base.actOnEntity(actor,id,command,key,csrf,origin);}
}
