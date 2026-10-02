package com.lianxibu.site.repository.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Evidence module owns physical validity columns and stable identity locking. */
public final class JdbcEvidenceValidity {
    public record State(String id, long revision, boolean valid) { }
    private static final Set<String> CATALOG = Set.of("agent", "skill", "workflow", "model");
    private final JdbcTemplate jdbc;

    public JdbcEvidenceValidity(DataSource source) { jdbc = new JdbcTemplate(Objects.requireNonNull(source)); }

    public State read(String kind, String id) {
        if (kind.equals("article_revision")) {
            var rows = jdbc.query("SELECT id,validity_revision,effective_valid FROM article_revisions WHERE id=?",
                    (rs,n)->new State(rs.getString(1),rs.getLong(2),rs.getBoolean(3)),id);
            return exact(rows,id);
        }
        var rows = jdbc.query("SELECT id,validity_revision,effective_valid,category FROM source_run_items WHERE id=?",
                (rs,n)->new Object[]{new State(rs.getString(1),rs.getLong(2),rs.getBoolean(3)),rs.getString(4)},id);
        if (rows.isEmpty()) return null;
        if (rows.size()!=1 || !CATALOG.contains(rows.getFirst()[1])) throw unavailable();
        return exact(List.of((State)rows.getFirst()[0]),id);
    }

    public void lock(String kind, String id) {
        requireTx();
        if (kind.equals("article_revision")) {
            var owners=jdbc.query("SELECT id,article_id FROM article_revisions WHERE id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},id);
            if(owners.size()!=1 || !id.equals(owners.getFirst()[0]) || owners.getFirst()[1]==null) throw unavailable();
            String owner=owners.getFirst()[1];
            var stable=jdbc.query("SELECT id FROM articles WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),owner);
            if(stable.size()!=1 || !owner.equals(stable.getFirst())) throw unavailable();
            var locked=jdbc.query("SELECT id,article_id FROM article_revisions WHERE id=? FOR UPDATE",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},id);
            if(locked.size()!=1 || !id.equals(locked.getFirst()[0]) || !owner.equals(locked.getFirst()[1])) throw unavailable();
        } else {
            var locked=jdbc.query("SELECT id,category FROM source_run_items WHERE id=? FOR UPDATE",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},id);
            if(locked.size()!=1 || !id.equals(locked.getFirst()[0]) || !CATALOG.contains(locked.getFirst()[1])) throw unavailable();
        }
        if(read(kind,id)==null) throw unavailable();
    }

    public void update(String kind, String id, long expected, long next, boolean valid) {
        requireTx(); lock(kind,id);
        if(expected<0 || next!=expected+1 || next>CanonicalJson.MAX_SAFE_INTEGER) throw unavailable();
        var old=read(kind,id); if(old.revision()!=expected) throw unavailable();
        String table=kind.equals("article_revision")?"article_revisions":"source_run_items";
        if(jdbc.update("UPDATE "+table+" SET validity_revision=?,effective_valid=? WHERE id=? AND validity_revision=?",next,valid,id,expected)!=1) throw unavailable();
    }

    private static State exact(List<State> rows,String id) {
        if(rows.isEmpty()) return null;
        if(rows.size()!=1 || !id.equals(rows.getFirst().id()) || rows.getFirst().revision()<0 || rows.getFirst().revision()>CanonicalJson.MAX_SAFE_INTEGER) throw unavailable();
        return rows.getFirst();
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("evidence requires shared write transaction");}
    private static IllegalStateException unavailable(){return new IllegalStateException("exact evidence validity unavailable");}
}
