package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native scalar root binding for owner unknown-call closure. No body or model authority. */
public final class JdbcOwnerJobClosureGuard {
    public record Root(String id,String kind,long revision,int calls,Integer maximum){ }
    private final JdbcTemplate sql;
    public JdbcOwnerJobClosureGuard(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Root root(String id){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();WireValues.id(id,false);
        var rows=sql.query("SELECT id,record_kind,revision,call_count,max_attempts FROM jobs WHERE id=?"+(TransactionSynchronizationManager.isCurrentTransactionReadOnly()?"":" FOR UPDATE"),(r,n)->{Long revision=r.getObject(3,Long.class);Integer calls=r.getObject(4,Integer.class);if(revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||calls==null||calls<0||calls>3)throw unavailable();return new Root(r.getString(1),r.getString(2),revision,calls,r.getObject(5,Integer.class));},id);
        if(rows.size()>1)throw unavailable();return rows.isEmpty()||!id.equals(rows.getFirst().id())?null:rows.getFirst();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("native owner closure binding unavailable");}
}
