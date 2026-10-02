package com.lianxibu.site.repository.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Content module writer. Joins the caller's shared transaction; never opens one. */
public final class JdbcGeneratedContents {
    public record Applied(String id,long revision,long bodyRevision) { }
    private final JdbcTemplate jdbc;
    public JdbcGeneratedContents(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Applied replace(String kind,String id,Long expected,String title,String body,byte[] bodyHash,String inputOwner,String entity,byte[] periodHash,Instant now) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("content write requires shared transaction");
        var rows=jdbc.query("SELECT kind,id,revision,body_revision,period_key_hash,entity_id FROM contents WHERE kind=? AND id=? FOR UPDATE",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getObject(4),rs.getBytes(5),rs.getString(6)},kind,id);
        if(rows.size()>1)throw new IllegalStateException("content identity not unique");
        if(rows.isEmpty()&&expected!=null||!rows.isEmpty()&&expected==null)return null;
        long revision=1,bodyRevision=1;
        if(!rows.isEmpty()) {
            var row=rows.getFirst();if(!kind.equals(row[0])||!id.equals(row[1]))throw new IllegalStateException("exact content identity required");
            long current=(long)row[2];if(current!=expected)return null;
            if(periodHash!=null&&!Arrays.equals(periodHash,(byte[])row[4])||entity!=null&&!entity.equals(row[5]))return null;
            long currentBody=row[3]==null?current:((Number)row[3]).longValue();
            if(current<1||current>=CanonicalJson.MAX_SAFE_INTEGER||currentBody<1||currentBody>=CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalStateException("content revision exhausted");
            revision=current+1;bodyRevision=currentBody+1;
        }
        var key=JsonMapper.shared().createObjectNode();key.put("kind","content_version");key.put("content_kind",kind);key.put("content_id",id);key.put("revision",bodyRevision);key.put("body_hash",HexFormat.of().formatHex(bodyHash));key.put("input_job_id",inputOwner);
        String versionJson=new String(CanonicalJson.bytes(key),java.nio.charset.StandardCharsets.UTF_8);
        if(rows.isEmpty()) {
            var natural=JsonMapper.shared().createObjectNode();natural.put("kind",kind);natural.put("id",id);
            try {jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision,body_revision,input_job_id,entity_id,period_key_hash,body_hash_v1,content_key_json,updated_at) VALUES(?,?,?,?,?,'draft',1,1,?,?,?,?,?,?)",kind,id,"generated-v1:"+CanonicalJson.hash(CanonicalJson.Domain.CONTENT_KEY,natural),title,body,inputOwner,entity,periodHash,bodyHash,versionJson,Timestamp.from(now));}
            catch(DuplicateKeyException collision){return null;}
        }else if(jdbc.update("UPDATE contents SET title=?,body_json=?,state='draft',revision=?,body_revision=?,input_job_id=?,entity_id=?,period_key_hash=?,body_hash_v1=?,content_key_json=?,review_hash_v1=NULL,review_dependency_stamp=NULL,review_stamp_hash=NULL,review_stamp_id=NULL,reviewed_input_hash=NULL,blocking_summary_json=NULL,published_at=NULL,updated_at=? WHERE kind=? AND id=? AND revision=?",title,body,revision,bodyRevision,inputOwner,entity,periodHash,bodyHash,versionJson,Timestamp.from(now),kind,id,expected)!=1)return null;
        return new Applied(id,revision,bodyRevision);
    }
}
