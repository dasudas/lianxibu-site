package com.lianxibu.site.repository.learning;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact current question and package facts; no visibility column is inferred. */
public final class JdbcLearningAnswers {
    public record Row(String packageId, String questionId, String title, String answer,
                      String state, String generationState, long revision, Long answerRevision,
                      byte[] reviewHash, boolean packagePublished) { }
    private final JdbcTemplate jdbc;
    public JdbcLearningAnswers(DataSource source) { jdbc = new JdbcTemplate(source); }
    public Row read(String pkg, String question, boolean lock) {
        if (lock) requireTx();
        var rows = jdbc.query("SELECT i.title,i.answer_text,i.answer_state,i.answer_generation_state,i.revision,"
                + "i.answer_revision,i.answer_review_hash_v1,p.state FROM learning_items i JOIN learning_packages p"
                + " ON p.id=i.package_id WHERE i.package_id=? AND i.kind='question' AND i.id=?" + (lock ? " FOR UPDATE" : ""),
                (rs,n) -> new Row(pkg,question,rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getObject(6,Long.class),rs.getBytes(7),"published".equals(rs.getString(8))),pkg,question);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public String receipt(String actor, byte[] key, byte[] hash) {
        var rows = jdbc.query("SELECT request_hash,response_json FROM request_receipts WHERE actor_id=? AND scope='learning-answer-v1' AND key_hash=?",
                (rs,n) -> { if (!Arrays.equals(hash,rs.getBytes(1))) throw new IllegalArgumentException("answer command conflict"); return rs.getString(2); },actor,key);
        if (rows.size()>1) throw new IllegalStateException("duplicate answer receipt");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void edit(Row row, String text, long answerRevision) {
        requireTx();
        changed(jdbc.update("UPDATE learning_items SET answer_text=?,answer_state='draft',answer_revision=?,answer_review_hash_v1=NULL,revision=revision+1 WHERE package_id=? AND kind='question' AND id=? AND revision=?",
                text,answerRevision,row.packageId(),row.questionId(),row.revision()));
    }
    public void transition(Row row, String state, byte[] hash) {
        requireTx();
        changed(jdbc.update("UPDATE learning_items SET answer_state=?,answer_review_hash_v1=?,revision=revision+1 WHERE package_id=? AND kind='question' AND id=? AND revision=?",
                state,hash,row.packageId(),row.questionId(),row.revision()));
    }
    public void receipt(String actor, String key, byte[] keyHash, byte[] hash, String result, Instant at) {
        requireTx();
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,original_status,current_status,created_at,revision,request_key_raw,business_hash) VALUES(?,'learning-answer-v1',?,?,200,?,NULL,?,'admin_action',?,'learning-answer-v1',?,'applied','applied',?,1,?,?)",
                actor,key,hash,result,actionId(actor,key),actor,keyHash,Timestamp.from(at),key,hash);
    }
    private static void changed(int rows) { if (rows!=1) throw new IllegalArgumentException("answer revision conflict"); }
    private static void requireTx() { if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new IllegalStateException("answer writer transaction required"); }
    public static String actionId(String actor,String key){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("learning-answer-v1\n"+actor+"\n"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
