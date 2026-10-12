package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcNewsReportLeaves;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Native reference and current validity for an exact original P2 member body. No current article projection is used. */
public final class NewsReportLeafEvidenceService {
    public record Original(String itemId,String runId,String sourceId,long inputRevision,String bodyHash,String bodyJson){
        public Original{WireValues.id(itemId,false);WireValues.id(runId,false);WireValues.id(sourceId,true);if(inputRevision<0||inputRevision>CanonicalJson.MAX_SAFE_INTEGER-2||bodyHash==null||!bodyHash.matches("[0-9a-f]{64}")||bodyJson==null||bodyJson.getBytes(StandardCharsets.UTF_8).length>204800)throw invalid();}
        @Override public String toString(){return "OriginalNewsReportLeaf[redacted]";}
    }
    public record Verified(String referenceJson,long validityRevision,boolean effectiveValid,boolean blocked){
        @Override public String toString(){return "VerifiedNewsReportLeaf[redacted]";}
    }
    private final JdbcNewsReportLeaves rows;
    public NewsReportLeafEvidenceService(JdbcNewsReportLeaves rows){this.rows=Objects.requireNonNull(rows);}
    /** Missing/legacy association is unproven, never a valid empty reference or an invented invalid version. */
    public Verified read(Original original,Instant cutoff){
        JdbcNewsReportLeaves.readonly();Objects.requireNonNull(original);if(cutoff==null||cutoff.getNano()%1000!=0)throw invalid();var payload=CanonicalJson.parse(original.bodyJson());ObservationPayloadPolicy.validateStored("article",payload,original.sourceId());
        if(!original.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload)))throw invalid();
        var member=rows.member(original.itemId());if(member==null||member.articleId()==null||member.revisionId()==null)return null;
        if(!original.itemId().equals(member.id())||!original.runId().equals(member.runId())||!original.sourceId().equals(member.sourceId())||!original.sourceId().equals(member.runSource())
            ||!"news_tech".equals(member.category())||member.revision()<original.inputRevision()+2||member.revision()>CanonicalJson.MAX_SAFE_INTEGER||!"production".equals(member.activation())||!Set.of("complete","partial").contains(member.delivery())
            ||!"accepted".equals(member.terminal())||!Set.of("pending","ready","analyzed","needs_review","excluded","failed").contains(member.state())||!Set.of("initialized","unchanged","promoted","historical","needs_review").contains(member.projection())||member.createdAt()==null||member.finishedAt()==null||member.createdAt().isAfter(cutoff)||member.finishedAt().isAfter(cutoff)||member.hash()==null)throw invalid();
        var stored=CanonicalJson.parse(member.payload());byte[] bytes=CanonicalJson.bytes(stored);
        if(!MessageDigest.isEqual(sha(bytes),member.hash())||!Arrays.equals(bytes,CanonicalJson.bytes(payload)))throw invalid();
        var revision=rows.revision(member.revisionId());if(revision==null||revision.legacy())return null;
        if(!member.revisionId().equals(revision.id())||!member.articleId().equals(revision.articleId())||revision.createdAt()==null||revision.createdAt().isAfter(cutoff)||revision.title()==null||revision.hash()==null)throw invalid();
        var projected=JsonMapper.shared().createObjectNode().put("title",revision.title()).put("summary",revision.summary()).put("body_text",revision.body());
        if(!CanonicalJson.hash(CanonicalJson.Domain.ARTICLE_PROJECTION,projected).equals(HexFormat.of().formatHex(revision.hash()))||!projected.get("title").equals(payload.get("title"))||!projected.get("summary").equals(payload.get("source_summary"))||!projected.get("body_text").equals(payload.get("body_text")))throw invalid();
        var target=JsonMapper.shared().createObjectNode().put("kind","article_revision").put("id",revision.id());String reference=new String(CanonicalJson.bytes(target),StandardCharsets.UTF_8);
        return new Verified(reference,revision.validityRevision(),member.valid()&&revision.valid(),!Set.of("ready","analyzed").contains(member.state())||"needs_review".equals(member.projection()));
    }
    private static byte[] sha(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IllegalStateException invalid(){return new IllegalStateException("exact original news report reference unavailable");}
}
