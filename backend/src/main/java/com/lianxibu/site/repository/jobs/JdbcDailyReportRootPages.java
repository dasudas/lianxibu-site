package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** All native daily report locators in a natural period. A locator is never a completion proof. */
public final class JdbcDailyReportRootPages {
    public record Page(List<String> roots,String nextCursor,boolean hasMore) {
        public Page{roots=List.copyOf(roots);}
        @Override public String toString(){return "DailyReportRootPage[redacted]";}
    }
    public static final int PAGE_SIZE=64;
    private final JdbcTemplate jdbc;
    public JdbcDailyReportRootPages(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Page read(LocalDate first,LocalDate end,String after){
        readonly();if(first==null||end==null||!end.isAfter(first)||java.time.temporal.ChronoUnit.DAYS.between(first,end)>31)throw invalid();
        if(after!=null)WireValues.id(after,false);
        List<Object> arguments=new ArrayList<>();arguments.add(first.toString());arguments.add(end.toString());
        List<byte[]> keys=new ArrayList<>();for(LocalDate day=first;day.isBefore(end);day=day.plusDays(1))keys.add(JdbcGenerationLineage.hash("report:daily:"+day));
        arguments.addAll(keys);if(after!=null)arguments.add(after);
        String sql="SELECT id FROM jobs WHERE record_kind='logical_task' AND ((task_type='daily_report' AND business_date>=? AND business_date<?) OR logical_key_hash IN ("+
            String.join(",",Collections.nCopies(keys.size(),"?"))+"))"+(after==null?"":" AND id>?")+" ORDER BY id LIMIT 65";
        var ids=jdbc.query(sql,(r,n)->WireValues.id(r.getString(1),false),arguments.toArray());
        if(new HashSet<>(ids).size()!=ids.size()||ids.size()>65)throw invalid();
        boolean more=ids.size()>PAGE_SIZE;var page=List.copyOf(ids.subList(0,Math.min(ids.size(),PAGE_SIZE)));
        return new Page(page,page.isEmpty()?after:page.getLast(),more);
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()
        ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native daily report root universe unavailable");}
    @Override public String toString(){return "JdbcDailyReportRootPages[redacted]";}
}
