package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.learning.JdbcLearningAnswers;
import com.lianxibu.site.service.learning.AnswerLifecycleService.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/** Request-scoped actor binding is part of the persistent receipt identity. */
public final class AnswerJdbcStore implements AnswerLifecycleService.Store {
    private final JdbcLearningAnswers store;
    private final String actor;
    private final Clock clock;
    public AnswerJdbcStore(JdbcLearningAnswers store,String actor,Clock clock) {
        this.store=java.util.Objects.requireNonNull(store); this.actor=WireValues.id(actor,false); this.clock=java.util.Objects.requireNonNull(clock);
    }
    @Override public Result committed(String key,String hash) {
        var value=store.receipt(actor,digest(key),HexFormat.of().parseHex(hash)); if(value==null)return null;
        var j=CanonicalJson.parse(value);
        return new Result(j.get("package_id").asText(),j.get("question_id").asText(),j.get("revision").longValue(),j.get("answer_revision").longValue(),j.get("answer_state").asText(),true);
    }
    @Override public Current locked(String pkg,String question) { return current(store.read(pkg,question,true)); }
    @Override public Current publicSnapshot(String pkg,String question) { return current(store.read(pkg,question,false)); }
    private static Current current(JdbcLearningAnswers.Row r) { return r==null?null:new Current(r.packageId(),r.questionId(),r.answer(),r.state(),r.revision(),r.answerRevision(),r.reviewHash(),r.packagePublished(),true); }
    @Override public void edit(Current row,String answer,long next,String hash,String key) {
        store.edit(row(row),answer,next); save(new Result(row.packageId(),row.questionId(),row.revision()+1,next,"draft",false),hash,key);
    }
    @Override public void transition(Current row,String state,byte[] review,String hash,String key) {
        store.transition(row(row),state,review); save(new Result(row.packageId(),row.questionId(),row.revision()+1,row.answerRevision()==null?0:row.answerRevision(),state,false),hash,key);
    }
    private static JdbcLearningAnswers.Row row(Current r) { return new JdbcLearningAnswers.Row(r.packageId(),r.questionId(),null,r.answerText(),r.answerState(),null,r.revision(),r.answerRevision(),r.reviewHash(),r.packagePublished()); }
    private void save(Result r,String hash,String key) {
        var j=JsonMapper.shared().createObjectNode();j.put("package_id",r.packageId());j.put("question_id",r.questionId());j.put("revision",r.revision());j.put("answer_revision",r.answerRevision());j.put("answer_state",r.answerState());
        store.receipt(actor,key,digest(key),HexFormat.of().parseHex(hash),j.toString(),clock.instant());
    }
    private static byte[] digest(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
