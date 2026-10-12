package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcArticlePublicEvidence;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Public citation facts for a new input, never a reconstruction from the current article. */
public final class ArticlePublicEvidenceService {
    public record Fact(String revisionId,String title,String sourceUrl) { }
    private final JdbcArticlePublicEvidence store;
    private final SourceMemberEvidenceService evidence;
    public ArticlePublicEvidenceService(JdbcArticlePublicEvidence store,SourceMemberEvidenceService evidence){this.store=Objects.requireNonNull(store);this.evidence=Objects.requireNonNull(evidence);}
    public Fact read(String revisionId,Instant cutoff) {
        WireValues.id(revisionId,false);if(cutoff==null)throw invalid();var row=store.lock(revisionId);
        if(row==null||!revisionId.equals(row.id())||row.legacy()||!row.valid()||row.createdAt()==null||row.createdAt().isAfter(cutoff)||row.hash()==null||row.title()==null||row.articleId()==null)throw invalid();
        var projected=JsonMapper.shared().createObjectNode().put("title",row.title()).put("summary",row.summary()).put("body_text",row.body());
        if(!CanonicalJson.hash(CanonicalJson.Domain.ARTICLE_PROJECTION,projected).equals(HexFormat.of().formatHex(row.hash())))throw invalid();
        var ids=store.members(row,cutoff);if(ids.isEmpty()||ids.size()>100||new HashSet<>(ids).size()!=ids.size())throw invalid();
        String url=null;
        for(var fact:evidence.read(Set.copyOf(ids),cutoff)) {
            if(!"news_tech".equals(fact.category()))throw invalid();var payload=CanonicalJson.parse(fact.payloadJson());
            if(!projected.get("title").equals(payload.get("title"))||!projected.get("summary").equals(payload.get("source_summary"))||!projected.get("body_text").equals(payload.get("body_text")))throw invalid();
            String candidate=WireValues.text(payload.get("source_url"));if(url!=null&&!url.equals(candidate))throw invalid();url=candidate;
        }
        if(url==null)throw invalid();return new Fact(revisionId,row.title(),url);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("article citation proof unavailable");}
}
