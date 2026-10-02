package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.content.JdbcContentReads.Row;
import com.lianxibu.site.repository.content.JdbcReviewPublication;
import java.time.*;
import java.util.*;

/** Narrow content module writer; frozen reference proof and receipts are supplied by callers. */
public final class ContentReviewWriteService {
    private final JdbcReviewPublication store;
    private final GeneratedContentService generated;
    private final Clock clock;
    public ContentReviewWriteService(JdbcReviewPublication store,GeneratedContentService generated,Clock clock){this.store=Objects.requireNonNull(store);this.generated=Objects.requireNonNull(generated);this.clock=Objects.requireNonNull(clock);}
    public Row lock(String kind,String id){WireValues.id(id,false);if(!Set.of("report","agent","skill","workflow","model").contains(kind))throw new IllegalArgumentException("invalid content kind");return store.lock(kind,id);}
    public ReviewContentState reviewSnapshot(String kind,String id){return ReviewContentState.fromRow(lock(kind,id));}
    public void approve(ReviewContentState expected,ReviewPublicationService.Stamp stamp,String stampId,byte[] inputHash){approve(expected.row(),stamp,stampId,inputHash);}
    public void publish(ReviewContentState expected){publish(expected.row());}
    public boolean closeIfCurrent(ContentVersionKey target,Long managementRevision,String reviewStamp,String dispositionId,boolean displayOnly){
        Objects.requireNonNull(target);if(target.inputJobId()==null)throw new IllegalArgumentException("input owner required");
        if(dispositionId!=null)WireValues.id(dispositionId,false);
        var row=lock(target.contentKind(),target.contentId());if(row==null||row.bodyRevision()==null||row.bodyHashV1()==null||row.bodyRevision()!=target.revision()||!HexFormat.of().formatHex(row.bodyHashV1()).equals(target.bodyHash())||!Objects.equals(row.inputJobId(),target.inputJobId()))return false;
        if(managementRevision!=null&&(row.revision()!=managementRevision||row.reviewStampHash()==null||!HexFormat.of().formatHex(row.reviewStampHash()).equals(reviewStamp)))return false;
        if(row.state().equals("withdrawn")&&row.reviewStampHash()==null)return false;
        var summary=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();summary.put("reason",displayOnly?"display_adjustment":"invalid_dependency");summary.put("disposition_id",dispositionId);
        return store.close(row,"withdrawn",new String(CanonicalJson.bytes(summary),java.nio.charset.StandardCharsets.UTF_8),now());
    }
    public void edit(ReviewContentState expected,String bodyJson,byte[] suppliedBodyHash){edit(expected.row(),bodyJson,suppliedBodyHash);}
    public void approve(Row expected,ReviewPublicationService.Stamp stamp,String stampId,byte[] inputHash){
        Objects.requireNonNull(stamp);WireValues.id(stampId,false);if(stamp.hash()==null||!stamp.hash().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid stamp hash");var vector=CanonicalJson.parse(stamp.json());if(!vector.isArray()||vector.size()>10000)throw new IllegalArgumentException("invalid review vector");
        store.approve(expected,new String(CanonicalJson.bytes(vector),java.nio.charset.StandardCharsets.UTF_8),HexFormat.of().parseHex(stamp.hash()),stampId,inputHash,now());
    }
    public void publish(Row expected){store.publish(expected,now());}
    public void edit(Row expected,String bodyJson,byte[] suppliedBodyHash){
        if(expected==null||suppliedBodyHash==null||!Arrays.equals(suppliedBodyHash,HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(bodyJson)))))throw new IllegalArgumentException("body hash mismatch");
        var applied=generated.write(new GeneratedContentService.Write(expected.kind(),expected.id(),expected.revision(),expected.inputJobId(),bodyJson));if(applied==null)throw new ReviewPublicationService.Conflict("revision_conflict");
    }
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
}
