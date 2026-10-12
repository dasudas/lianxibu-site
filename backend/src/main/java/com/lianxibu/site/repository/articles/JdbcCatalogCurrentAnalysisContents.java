package com.lianxibu.site.repository.articles;

import com.lianxibu.site.domain.protocol.WireValues;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native current content locators only. The caller must separately prove the original successful analysis. */
public final class JdbcCatalogCurrentAnalysisContents {
    private final JdbcTemplate jdbc;
    private final String category;
    public JdbcCatalogCurrentAnalysisContents(DataSource source,String category){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
    }
    public Map<String,String> read(Set<String> entities){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
        Objects.requireNonNull(entities);if(entities.size()>100)throw new CapacityRejected();var expected=new TreeSet<String>();for(String id:entities)expected.add(WireValues.id(id,false));if(expected.isEmpty())return Map.of();
        var args=new ArrayList<Object>();args.add(category);args.addAll(expected);
        String placeholders=String.join(",",Collections.nCopies(expected.size(),"?"));
        var rows=jdbc.query("SELECT CASE WHEN OCTET_LENGTH(id) BETWEEN 1 AND 256 THEN id ELSE NULL END,kind,CASE WHEN OCTET_LENGTH(entity_id) BETWEEN 1 AND 256 THEN entity_id ELSE NULL END FROM contents WHERE kind=? AND entity_id IN ("+placeholders+") ORDER BY entity_id,id LIMIT "+(expected.size()+1),(row,n)->new String[]{row.getString(1),row.getString(2),row.getString(3)},args.toArray());
        var found=new HashMap<String,String>();for(var row:rows){String id=WireValues.id(row[0],false),entity=WireValues.id(row[2],false);if(!category.equals(row[1])||!expected.contains(entity)||found.putIfAbsent(entity,id)!=null)throw invalid();}
        return Map.copyOf(found);
    }
    public static final class CapacityRejected extends RuntimeException {public CapacityRejected(){super("catalog locator capacity rejected");}}
    private static IllegalStateException invalid(){return new IllegalStateException("native catalog analysis locator unavailable");}
}
