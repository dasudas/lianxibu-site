package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.content.ReviewPublicationService.Receipt;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Jobs-owned audit port; current body proof is obtained through the content service. */
public final class ContentReviewEvidenceService {
    private final JdbcContentReviewEvidence store;
    private final JdbcFrozenInputs inputs;
    private final JdbcResultState roots;
    private final FrozenContentVersionService versions;
    private final ContentReadService contents;
    private final Clock clock;
    private final String actor;
    public ContentReviewEvidenceService(DataSource source,ContentReadService contents,FrozenContentVersionService versions,Clock clock,String actor){store=new JdbcContentReviewEvidence(source);inputs=new JdbcFrozenInputs(source);roots=new JdbcResultState(source);this.contents=Objects.requireNonNull(contents);this.versions=Objects.requireNonNull(versions);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);}
    public FrozenContentVersionService.Proven archiveCurrent(ContentVersionKey target){
        JdbcFrozenInputs.requireTx();var row=contents.administrativeVersion(target.contentKind(),target.contentId()).orElseThrow(ContentReviewEvidenceService::invalid);
        if(!row.kind().equals(target.contentKind())||!row.id().equals(target.contentId())||row.bodyRevision()!=target.revision()||!Objects.equals(row.inputJobId(),target.inputJobId())||row.bodyHash()==null||!target.bodyHash().equals(HexFormat.of().formatHex(row.bodyHash())))throw invalid();
        var body=CanonicalJson.parse(row.bodyJson());if(!target.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw invalid();
        var root=roots.root(target.inputJobId());var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(inputs.read(target.inputJobId(),root.hash())).get(FrozenApplicationContext.FIELD));if(!target.contentKind().equals(context.contentKind())||!target.contentId().equals(context.contentId()))throw invalid();
        var allowed=context.input(target.inputJobId(),"archive-proof",root.hash(),0).allowedCitationIds();checkCitations(body,allowed);
        var existing=versions.find(target);if(existing.isPresent()){if(!existing.get().bodyJson().equals(JdbcFrozenInputs.canonical(body)))throw invalid();return existing.get();}
        store.archive(target,JdbcFrozenInputs.canonical(body),root.hash(),actor,now());return versions.find(target).orElseThrow(ContentReviewEvidenceService::invalid);
    }
    public Receipt committed(String key,String commandHash){
        hash(commandHash);var payload=store.receipt(key,actor,commandHash);if(payload==null)return null;
        WireValues.object(payload,Set.of("schema_version","command_hash","kind","id","revision","state","stamp_hash","input_hash","content_version"),Set.of("schema_version","command_hash","kind","id","revision","state","stamp_hash","input_hash","content_version"));
        var version=ContentVersionKey.fromJson(payload.get("content_version"));var proof=versions.find(version).orElseThrow(ContentReviewEvidenceService::invalid);if(!proof.inputHash().equals(payload.path("input_hash").asText())||!version.contentKind().equals(payload.path("kind").asText())||!version.contentId().equals(payload.path("id").asText()))throw invalid();
        long revision=WireValues.nonnegativeInteger(payload.get("revision"));if(revision<1)throw invalid();String state=WireValues.text(payload.get("state"));if(!Set.of("draft","approved","published").contains(state))throw invalid();String stamp=payload.path("stamp_hash").isNull()?null:WireValues.sha256(payload.get("stamp_hash"));return new Receipt(WireValues.id(payload.get("kind"),false),WireValues.id(payload.get("id"),false),revision,state,stamp);
    }
    public void append(String key,String commandHash,String reason,Receipt receipt,ContentVersionKey version){
        JdbcFrozenInputs.requireTx();hash(commandHash);var proof=archiveCurrent(version);var row=contents.administrativeVersion(version.contentKind(),version.contentId()).orElseThrow(ContentReviewEvidenceService::invalid);if(!receipt.kind().equals(version.contentKind())||!receipt.id().equals(version.contentId())||receipt.revision()!=row.revision()||!receipt.state().equals(row.state()))throw invalid();
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","content-review-receipt-v1");payload.put("command_hash",commandHash);payload.put("kind",receipt.kind());payload.put("id",receipt.id());payload.put("revision",receipt.revision());payload.put("state",receipt.state());payload.put("stamp_hash",receipt.stampHash());payload.put("input_hash",proof.inputHash());payload.set("content_version",CanonicalJson.parse(JdbcFrozenContentVersions.version(version)));store.append(key,actor,reason,receipt.kind(),receipt.id(),payload,now());
    }
    private static void checkCitations(JsonNode body,Set<String> allowed){checkIds(body.get("citation_ids"),allowed);var sections=body.get("sections");if(sections==null||!sections.isArray())throw invalid();for(var section:sections)checkIds(section.get("citation_ids"),allowed);}
    private static void checkIds(JsonNode ids,Set<String> allowed){if(ids==null||!ids.isArray())throw invalid();for(var id:ids)if(!id.isTextual()||!allowed.contains(id.asText()))throw invalid();}
    private static void hash(String hash){if(hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid();}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static IllegalStateException invalid(){return new IllegalStateException("current content or frozen review proof unavailable");}
}
