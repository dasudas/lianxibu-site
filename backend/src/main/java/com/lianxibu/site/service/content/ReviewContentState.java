package com.lianxibu.site.service.content;
import com.lianxibu.site.repository.content.JdbcContentReads.Row;
import java.time.Instant;

/** Immutable service value for review orchestration; repository rows stay in content. */
public record ReviewContentState(String kind,String id,String naturalKey,String title,String bodyJson,
        String state,long revision,Long bodyRevision,Instant publishedAt,String inputJobId,
        byte[] bodyHashV1,byte[] reviewHashV1,String reviewDependencyStamp,byte[] reviewStampHash,
        String reviewStampId,byte[] reviewedInputHash,String blockingSummaryJson) {
    public ReviewContentState {bodyHashV1=copy(bodyHashV1);reviewHashV1=copy(reviewHashV1);reviewStampHash=copy(reviewStampHash);reviewedInputHash=copy(reviewedInputHash);}
    @Override public byte[] bodyHashV1(){return copy(bodyHashV1);}
    @Override public byte[] reviewHashV1(){return copy(reviewHashV1);}
    @Override public byte[] reviewStampHash(){return copy(reviewStampHash);}
    @Override public byte[] reviewedInputHash(){return copy(reviewedInputHash);}
    static ReviewContentState fromRow(Row r){return r==null?null:new ReviewContentState(r.kind(),r.id(),r.naturalKey(),r.title(),r.bodyJson(),r.state(),r.revision(),r.bodyRevision(),r.publishedAt(),r.inputJobId(),r.bodyHashV1(),r.reviewHashV1(),r.reviewDependencyStamp(),r.reviewStampHash(),r.reviewStampId(),r.reviewedInputHash(),r.blockingSummaryJson());}
    Row row(){return new Row(kind,id,naturalKey,title,bodyJson,state,revision,bodyRevision,publishedAt,inputJobId,bodyHashV1(),reviewHashV1(),reviewDependencyStamp,reviewStampHash(),reviewStampId,reviewedInputHash(),blockingSummaryJson);}
    private static byte[] copy(byte[] value){return value==null?null:value.clone();}
}
