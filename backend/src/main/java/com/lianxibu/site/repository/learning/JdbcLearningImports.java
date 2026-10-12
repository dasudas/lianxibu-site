package com.lianxibu.site.repository.learning;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only explicitly supplied objects and their declared relationships are replaced. */
public final class JdbcLearningImports {
    public record Package(String id,long revision) { }
    private final JdbcTemplate jdbc;
    public JdbcLearningImports(DataSource source){jdbc=new JdbcTemplate(source);}
    public String receipt(String actor,String key,byte[] hash){
        var rows=jdbc.query("SELECT request_hash,response_json FROM request_receipts WHERE principal=? AND operation='learning-import-v1' AND request_key=?",
                (rs,n)->{if(!Arrays.equals(hash,rs.getBytes(1)))throw new IllegalArgumentException("import command conflict");return rs.getString(2);},actor,key);
        if(rows.size()>1)throw new IllegalStateException("duplicate import receipt");return rows.isEmpty()?null:rows.getFirst();
    }
    public long begin(String id,Long expected,String title,Instant now){
        requireTx();var rows=jdbc.query("SELECT revision FROM learning_packages WHERE id=? FOR UPDATE",(rs,n)->rs.getLong(1),id);
        if(expected==null){if(!rows.isEmpty())throw new IllegalArgumentException("package identity conflict");jdbc.update("INSERT INTO learning_packages(id,title,state,revision,content_hash,updated_at) VALUES(?,?,'importing',1,NULL,?)",id,title,Timestamp.from(now));return 1;}
        if(rows.size()!=1||!expected.equals(rows.getFirst()))throw new IllegalArgumentException("package revision conflict");
        if(jdbc.update("UPDATE learning_packages SET title=?,state='draft',revision=revision+1,content_hash=NULL,updated_at=? WHERE id=? AND revision=?",title,Timestamp.from(now),id,expected)!=1)throw new IllegalArgumentException("package revision conflict");return expected+1;
    }
    public void item(String pkg,String kind,String id,String title,String body,Long expected,String answer,boolean answerSupplied,int position){
        requireTx();var rows=jdbc.query("SELECT revision FROM learning_items WHERE package_id=? AND kind=? AND id=? FOR UPDATE",(rs,n)->rs.getLong(1),pkg,kind,id);
        if(rows.isEmpty()){
            jdbc.update("INSERT INTO learning_items(package_id,kind,id,title,position_no,body_json,answer_text,answer_state,revision,answer_generation_state,answer_revision,answer_review_hash_v1) VALUES(?,?,?,?,?,?,?,?,1,?,?,NULL)",
                    pkg,kind,id,title,position,body,"question".equals(kind)&&answerSupplied?answer:null,"question".equals(kind)?"draft":null,"question".equals(kind)?(answerSupplied?"needs_revision":"not_generated"):null,"question".equals(kind)&&answerSupplied?1L:null);
        }else{
            long revision=rows.getFirst();if(expected!=null&&expected!=revision)throw new IllegalArgumentException("learning item revision conflict");
            if(jdbc.update("UPDATE learning_items SET title=?,body_json=?,revision=revision+1 WHERE package_id=? AND kind=? AND id=? AND revision=?",title,body,pkg,kind,id,revision)!=1)throw new IllegalArgumentException("learning item revision conflict");
            if("question".equals(kind)) {
                if(answerSupplied)jdbc.update("UPDATE learning_items SET answer_text=?,answer_revision=COALESCE(answer_revision,0)+1 WHERE package_id=? AND kind='question' AND id=?",answer,pkg,id);
                jdbc.update("UPDATE learning_items SET answer_state='draft',answer_review_hash_v1=NULL,answer_generation_state=CASE WHEN answer_text IS NULL THEN 'not_generated' ELSE 'needs_revision' END WHERE package_id=? AND kind='question' AND id=?",pkg,id);
            }
        }
    }
    public Set<String> itemIdentities(String pkg){
        requireTx();var rows=jdbc.query("SELECT kind,id FROM learning_items WHERE package_id=? ORDER BY kind,id LIMIT 10001",(rs,n)->rs.getString(1)+":"+rs.getString(2),pkg);
        if(rows.size()>10000)throw new IllegalStateException("capacity_guard");return Set.copyOf(rows);
    }
    public void clearOutgoing(String pkg,String kind,String id,String relation,String toKind){requireTx();jdbc.update("DELETE FROM learning_links WHERE package_id=? AND from_kind=? AND from_id=? AND relation_kind=? AND to_kind=?",pkg,kind,id,relation,toKind);}
    public void clearIncoming(String pkg,String kind,String id,String relation,String fromKind){requireTx();jdbc.update("DELETE FROM learning_links WHERE package_id=? AND to_kind=? AND to_id=? AND relation_kind=? AND from_kind=?",pkg,kind,id,relation,fromKind);}
    public void link(String pkg,String fromKind,String from,String relation,String toKind,String to,int position){
        requireTx();if(jdbc.queryForObject("SELECT COUNT(*) FROM learning_links WHERE package_id=? AND from_kind=? AND from_id=? AND relation_kind=? AND to_kind=? AND to_id=?",Integer.class,pkg,fromKind,from,relation,toKind,to)==0)
            jdbc.update("INSERT INTO learning_links(package_id,from_kind,from_id,relation_kind,to_kind,to_id,position_no) VALUES(?,?,?,?,?,?,?)",pkg,fromKind,from,relation,toKind,to,position);
    }
    public void finish(String pkg,long revision){requireTx();if(jdbc.update("UPDATE learning_packages SET state='draft' WHERE id=? AND revision=? AND state IN ('draft','importing')",pkg,revision)!=1)throw new IllegalArgumentException("package revision conflict");}
    public void receipt(String actor,String key,byte[] keyHash,byte[] hash,String pkg,long revision,String result,Instant now){
        requireTx();jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,original_status,current_status,created_at,revision,request_key_raw,business_hash) VALUES(?,'learning-import-v1',?,?,200,?,NULL,?,'admin_action',?,?,?,'applied','applied',?,?,?,?)",
                actor,key,hash,result,UUID.randomUUID().toString(),actor,"learning-import-package/"+pkg,keyHash,Timestamp.from(now),revision,key,hash);
    }
    public String metadata(String pkg,long revision){var rows=jdbc.query("SELECT response_json FROM request_receipts WHERE operation='learning-import-v1' AND scope=? AND revision<=? ORDER BY revision DESC LIMIT 1",(rs,n)->rs.getString(1),"learning-import-package/"+pkg,revision);return rows.isEmpty()?null:rows.getFirst();}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("import writer transaction required");}
}
