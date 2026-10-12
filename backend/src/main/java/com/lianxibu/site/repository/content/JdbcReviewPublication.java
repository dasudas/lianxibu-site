package com.lianxibu.site.repository.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Content-only CAS operations. Permanent action receipts are owned by the orchestrator. */
public final class JdbcReviewPublication {
    private final JdbcTemplate jdbc;
    private final JdbcContentReads reads;
    public JdbcReviewPublication(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));reads=new JdbcContentReads(source);}
    public JdbcContentReads.Row lock(String kind,String id){
        requireTx();var keys=jdbc.query("SELECT kind,id FROM contents WHERE kind=? AND id=? FOR UPDATE",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},kind,id);
        if(keys.isEmpty())return null;if(keys.size()!=1||!kind.equals(keys.getFirst()[0])||!id.equals(keys.getFirst()[1]))throw conflict();return reads.current(kind,id);
    }
    public void approve(JdbcContentReads.Row expected,String vector,byte[] stampHash,String stampId,byte[] inputHash,Instant now){
        requireTx();requireCurrent(expected);if(stampHash==null||stampHash.length!=32||inputHash==null||inputHash.length!=32||stampId==null||!CanonicalJson.parse(vector).isArray())throw conflict();
        one(jdbc.update("UPDATE contents SET state='approved',revision=revision+1,review_hash_v1=body_hash_v1,review_dependency_stamp=?,review_stamp_hash=?,review_stamp_id=?,reviewed_input_hash=?,blocking_summary_json=NULL,published_at=NULL,updated_at=? WHERE kind=? AND id=? AND revision=? AND body_revision=? AND body_hash_v1=? AND input_job_id=?",vector,stampHash,stampId,inputHash,Timestamp.from(now),expected.kind(),expected.id(),expected.revision(),expected.bodyRevision(),expected.bodyHashV1(),expected.inputJobId()));
    }
    public void publish(JdbcContentReads.Row expected,Instant now){
        requireTx();requireCurrent(expected);if(!"approved".equals(expected.state())||expected.reviewStampHash()==null||expected.reviewStampId()==null)throw conflict();
        one(jdbc.update("UPDATE contents SET state='published',revision=revision+1,published_at=?,updated_at=? WHERE kind=? AND id=? AND revision=? AND state='approved' AND body_revision=? AND body_hash_v1=? AND input_job_id=? AND review_stamp_hash=? AND review_stamp_id=?",Timestamp.from(now),Timestamp.from(now),expected.kind(),expected.id(),expected.revision(),expected.bodyRevision(),expected.bodyHashV1(),expected.inputJobId(),expected.reviewStampHash(),expected.reviewStampId()));
    }
    /** Qualification update is conditional on the original management/body/input/stamp identity. */
    public void reject(JdbcContentReads.Row expected,Instant now){
        requireTx();requireCurrent(expected);
        one(jdbc.update("UPDATE contents SET state='draft',revision=revision+1,review_hash_v1=NULL,review_dependency_stamp=NULL,review_stamp_hash=NULL,review_stamp_id=NULL,reviewed_input_hash=NULL,blocking_summary_json=NULL,published_at=NULL,updated_at=? WHERE kind=? AND id=? AND revision=? AND body_revision=? AND body_hash_v1=? AND input_job_id=?",Timestamp.from(now),expected.kind(),expected.id(),expected.revision(),expected.bodyRevision(),expected.bodyHashV1(),expected.inputJobId()));
    }
    /** Qualification update is conditional on the original management/body/input/stamp identity. */
    public boolean close(JdbcContentReads.Row expected,String state,String summary,Instant now){
        requireTx();requireCurrent(expected);if(!state.equals("withdrawn")||!CanonicalJson.parse(summary).isObject())throw conflict();
        String stamp=expected.reviewStampHash()==null?"review_stamp_hash IS NULL":"review_stamp_hash=?";
        var args=new ArrayList<Object>(List.of(state,summary,Timestamp.from(now),expected.kind(),expected.id(),expected.revision(),expected.bodyRevision(),expected.bodyHashV1(),expected.inputJobId()));
        if(expected.reviewStampHash()!=null)args.add(expected.reviewStampHash());
        return jdbc.update("UPDATE contents SET state=?,revision=revision+1,review_hash_v1=NULL,review_dependency_stamp=NULL,review_stamp_hash=NULL,review_stamp_id=NULL,reviewed_input_hash=NULL,blocking_summary_json=?,published_at=NULL,updated_at=? WHERE kind=? AND id=? AND revision=? AND body_revision=? AND body_hash_v1=? AND input_job_id=? AND "+stamp,args.toArray())==1;
    }
    private static void requireCurrent(JdbcContentReads.Row row){if(row==null||row.revision()<1||row.revision()>=CanonicalJson.MAX_SAFE_INTEGER||row.bodyRevision()==null||row.bodyRevision()<1||row.bodyHashV1()==null||row.bodyHashV1().length!=32||row.inputJobId()==null)throw conflict();}
    private static void one(int count){if(count!=1)throw conflict();}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("review requires shared writer transaction");}
    private static IllegalStateException conflict(){return new IllegalStateException("content review CAS conflict or proof unavailable");}
}
