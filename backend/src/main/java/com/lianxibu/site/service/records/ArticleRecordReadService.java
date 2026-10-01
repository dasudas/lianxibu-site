package com.lianxibu.site.service.records;

import com.lianxibu.site.repository.records.JdbcArticleRecordReads;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One read-only repeatable-read snapshot for rows, filtered total and full statistics. */
public final class ArticleRecordReadService {
    public record Article(String id,String title,String sourceName) { }
    public record Item(Article article,RecordsService.Record record) { }
    public record Stats(long total,long favorite,long planned,long learning,long completed) { }
    public record Page(List<Item> items,long page,int pageSize,long total,Stats stats) { }
    private static final long MAX=9007199254740991L;
    private final JdbcArticleRecordReads store;
    private final TransactionTemplate reads;
    public ArticleRecordReadService(JdbcArticleRecordReads store,PlatformTransactionManager tx) {
        if(store==null||tx==null)throw new IllegalArgumentException("read dependencies required");this.store=store;reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Page list(Actor actor,String q,String status,Boolean favorite,long page,int size) {
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(actor.role()==null||!Set.of("user","owner").contains(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        if(q!=null&&(q.isEmpty()||q.codePointCount(0,q.length())>256)||status!=null&&!Set.of("not_started","planned","learning","completed").contains(status)||page<1||page>MAX||size<1||size>50||page>Long.MAX_VALUE/size)throw new IllegalArgumentException("invalid article record query");
        return reads.execute(tx->{var result=store.page(actor.userId(),q,status,favorite,page,size);var s=result.stats();
            for(long count:new long[]{result.total(),s.total(),s.favorite(),s.planned(),s.learning(),s.completed()})if(count<0||count>MAX)throw unavailable();
            if(result.total()>s.total()||s.favorite()>s.total()||s.planned()>s.total()-s.learning()||s.planned()+s.learning()>s.total()-s.completed())throw unavailable();
            return new Page(result.items().stream().map(item->{var a=item.article();return new Item(a==null?null:new Article(a.id(),a.title(),a.sourceName()),RecordReadService.project(item.record()));}).toList(),page,size,result.total(),new Stats(s.total(),s.favorite(),s.planned(),s.learning(),s.completed()));
        });
    }
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
