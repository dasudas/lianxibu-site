package com.lianxibu.site.service.records;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.records.JdbcRecordWrites.Row;
import com.lianxibu.site.repository.records.JdbcRecordWrites;
import com.lianxibu.site.service.records.PrivateRecordWriteService.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Composition port keeps learning/article stores outside the records module. */
public final class RecordJdbcStore implements PrivateRecordWriteService.Store {
    public interface Visibility {
        Long bodyRevision(String packageId,String kind,String id);
        List<String> routeNodes(String packageId,String routeId);
    }
    private final JdbcRecordWrites store;
    private final Visibility visible;
    public RecordJdbcStore(JdbcRecordWrites store,Visibility visible){this.store=java.util.Objects.requireNonNull(store);this.visible=java.util.Objects.requireNonNull(visible);}
    @Override public RecordsService.Record committed(String user,String action,String hash){
        String value=store.committed(user,digest(action),HexFormat.of().parseHex(hash));
        if(value==null)return null;var j=CanonicalJson.parse(value);
        return new RecordsService.Record(text(j,"package_id"),text(j,"object_kind"),text(j,"object_id"),j.get("revision").longValue(),text(j,"status"),text(j,"self_assessment"),text(j,"note"),text(j,"artifact_url"),text(j,"latest_answer_text"),text(j,"latest_submission_url"),j.get("confirmed_body_revision").longValue(),Instant.parse(text(j,"updated_at")),j.get("is_read").booleanValue(),j.get("is_favorite").booleanValue(),j.get("is_ignored").booleanValue());
    }
    @Override public Current currentForUpdate(Key key){
        Row r=store.locked(key.userId(),key.packageId(),key.kind(),key.objectId());
        return r==null?null:new Current(r.revision(),r.status(),r.selfAssessment(),r.note(),r.artifactUrl(),r.responseText(),r.confirmedBodyRevision(),r.isRead(),r.isFavorite(),r.isIgnored());
    }
    @Override public Long visibleBodyRevision(String pkg,String kind,String id){return visible.bodyRevision(pkg,kind,id);}
    @Override public List<String> routeNodes(String pkg,String id){return visible.routeNodes(pkg,id);}
    @Override public Set<String> completedNodes(String user,String pkg,Set<String> ids){return store.completed(user,pkg,ids);}
    @Override public void insert(Key key,Value value,String action,String hash){Row row=row(key,value,1);store.insert(row);receipt(row,action,hash);}
    @Override public void update(Key key,long revision,Value value,String action,String hash){Row row=row(key,value,Math.addExact(revision,1));store.update(row,revision);receipt(row,action,hash);}
    private static Row row(Key k,Value v,long revision){return new Row(k.userId(),k.packageId(),k.kind(),k.objectId(),revision,v.status(),v.assessment(),v.note(),v.artifactUrl(),v.responseText(),v.confirmedBodyRevision(),v.updatedAt(),v.read(),v.favorite(),v.ignored());}
    private void receipt(Row row,String action,String hash){
        var r=new RecordsService.Record("article".equals(row.objectKind())?null:row.packageId(),row.objectKind(),row.objectId(),row.revision(),row.status(),row.selfAssessment(),row.note(),row.artifactUrl(),"question".equals(row.objectKind())?row.responseText():null,"project".equals(row.objectKind())?row.responseText():null,row.confirmedBodyRevision(),row.updatedAt(),row.isRead(),row.isFavorite(),row.isIgnored());var j=JsonMapper.shared().createObjectNode();
        j.put("package_id",r.packageId());j.put("object_kind",r.objectKind());j.put("object_id",r.objectId());j.put("revision",r.revision());j.put("status",r.status());j.put("self_assessment",r.selfAssessment());j.put("note",r.note());j.put("artifact_url",r.artifactUrl());j.put("latest_answer_text",r.latestAnswerText());j.put("latest_submission_url",r.latestSubmissionUrl());j.put("confirmed_body_revision",r.confirmedBodyRevision());j.put("updated_at",r.updatedAt().toString());j.put("is_read",r.isRead());j.put("is_favorite",r.isFavorite());j.put("is_ignored",r.isIgnored());
        store.receipt(row.userId(),action,digest(action),HexFormat.of().parseHex(hash),j.toString(),row.revision(),row.updatedAt());
    }
    private static String text(JsonNode j,String key){return j.get(key).isNull()?null:j.get(key).asText();}
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
