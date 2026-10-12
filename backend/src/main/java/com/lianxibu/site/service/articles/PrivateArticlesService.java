package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.articles.JdbcArticleReads;
import com.lianxibu.site.repository.articles.JdbcPrivateArticleReads;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.records.RecordReadService;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private article and owner entity reads in a single database snapshot. */
public final class PrivateArticlesService implements ArticlesService {
    private final JdbcPrivateArticleReads articles;
    private final JdbcArticleReads entities;
    private final RecordReadService records;
    private final TransactionTemplate reads;
    public PrivateArticlesService(JdbcPrivateArticleReads articles,JdbcArticleReads entities,RecordReadService records,PlatformTransactionManager tx){
        this.articles=articles;this.entities=entities;this.records=records;
        reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public ArticlePage listArticles(Actor actor,String source,String query,String status,long page,int size){
        authorize(actor,false);page(page,size);query(query);if(source!=null)WireValues.id(source,true);
        if(status!=null&&!Set.of("not_started","planned","learning","completed").contains(status))throw new IllegalArgumentException("invalid status");
        return reads.execute(tx->{var result=articles.page(actor.userId(),source,query,status,page,size);count(result.total());return new ArticlePage(result.items().stream().map(row->project(actor,row)).toList(),page,size,result.total());});
    }
    @Override public Article article(Actor actor,String id){authorize(actor,false);WireValues.id(id,false);return reads.execute(tx->{var row=articles.article(id);if(row==null)throw missing();return project(actor,row);});}
    @Override public EntityPage listEntities(Actor actor,String category,String query,long page,int size){
        authorize(actor,true);page(page,size);query(query);if(category!=null&&!Set.of("news_tech","agent","skill","workflow","model").contains(category))throw new IllegalArgumentException("invalid category");
        return reads.execute(tx->{var result=entities.entities(category,query,page,size);count(result.total());return new EntityPage(result.items().stream().map(PrivateArticlesService::entity).toList(),page,size,result.total());});
    }
    @Override public Entity entity(Actor actor,String id){authorize(actor,true);WireValues.id(id,false);return reads.execute(tx->{var value=entities.entity(id);if(value==null)throw missing();return entity(value);});}
    private Article project(Actor actor,JdbcPrivateArticleReads.Row row){
        var a=row.article();count(a.revision());
        var r=records.current(actor,"article",null,a.id()).orElse(null);
        Record record=r==null?null:new Record(null,"article",r.objectId(),r.revision(),r.status(),r.selfAssessment(),r.note(),r.artifactUrl(),null,null,r.confirmedBodyRevision(),r.updatedAt(),r.isRead(),r.isFavorite(),r.isIgnored());
        String status=row.contentStatus();if(status==null||status.isBlank())status="unknown";
        if(status.codePointCount(0,status.length())>32)throw new IllegalStateException("article status unavailable");
        return new Article(a.id(),a.title(),a.summary(),a.sourceId(),a.sourceName(),a.sourceUrl(),a.publishedAt(),a.observedAt(),null,null,status,a.revision(),record);
    }
    private static Entity entity(JdbcArticleReads.EntityRow value){
        count(value.revision());if((value.occurrenceCount()==null)!=(value.repeatCount()==null))throw new IllegalStateException("entity counts unavailable");
        if(value.occurrenceCount()!=null){count(value.occurrenceCount());if(value.repeatCount()!=Math.max(0,value.occurrenceCount()-1))throw new IllegalStateException("entity counts unavailable");}
        String state=value.analysisState();if(state==null||!Set.of("pending","queued","analyzed","failed","needs_review").contains(state))state=null;
        return new Entity(value.id(),value.category(),value.title(),value.summary(),value.firstRecordedAt(),value.firstSeenBusinessDate(),value.lastSeenAt(),value.occurrenceCount(),value.repeatCount(),state,value.revision());
    }
    @Override public ActionResult actOnEntity(Actor actor,String id,EntityAction command,String key,String csrf,String origin){authorize(actor,true);throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
    private static void authorize(Actor actor,boolean owner){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!("owner".equals(actor.role())||!owner&&"user".equals(actor.role())))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static void count(long n){if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalStateException("article value unavailable");}
    private static void page(long page,int size){if(page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid page");}
    private static void query(String q){if(q!=null&&(q.isEmpty()||q.codePointCount(0,q.length())>256))throw new IllegalArgumentException("invalid query");}
    private static IdentityService.Rejected missing(){return new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null);}
}
