package com.lianxibu.site.repository.jobs;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded cyclic locators only. Every event still needs its complete native proof. */
public final class JdbcEntityHeatEventLocators {
    public record Page(List<String> ids,boolean hasMore){public Page{ids=List.copyOf(ids);}@Override public String toString(){return "EntityHeatLocatorPage[redacted]";}}
    private final JdbcTemplate sql;
    public JdbcEntityHeatEventLocators(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Page page(String after,int limit){readonly();if(after==null||!after.isEmpty()&&!after.matches("[0-9a-f]{64}")||limit<1||limit>64)throw invalid();var rows=sql.query("SELECT id FROM jobs WHERE record_kind='disposition' AND target_type='entity_decision' AND id>? ORDER BY id LIMIT ?",(r,n)->r.getString(1),after,limit+1);String previous=after;for(String id:rows){if(id==null||!id.matches("[0-9a-f]{64}")||id.compareTo(previous)<=0)throw invalid();previous=id;}return new Page(rows.subList(0,Math.min(limit,rows.size())),rows.size()>limit);}
    private static void readonly(){Integer i=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||i==null||i!=TransactionDefinition.ISOLATION_READ_COMMITTED&&i!=TransactionDefinition.ISOLATION_REPEATABLE_READ)throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("entity count observation locators unavailable");}
}
