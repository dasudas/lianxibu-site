package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.WireValues;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
/** Scheduling hints only. Full native acceptance must be verified before preparation. */
public final class JdbcAdHocIntentScan {
    public record Page(List<String> intentIds,String nextCursor){
        public Page {intentIds=List.copyOf(intentIds);}
        @Override public String toString(){return "AdHocIntentPage[redacted]";}
    }
    private final JdbcTemplate sql;
    public JdbcAdHocIntentScan(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Page page(String after,int limit){
        JdbcSourceDedupResults.requireReadOnly();if(limit<1||limit>50)throw invalid();if(after!=null)WireValues.id(after,false);
        var rows=sql.query("SELECT id FROM jobs WHERE record_kind='generation_intent' AND task_type='ad_hoc_ai' AND state='waiting_input'"+(after==null?"":" AND id>?")+" ORDER BY id LIMIT ?",(r,n)->WireValues.id(r.getString(1),false),after==null?new Object[]{limit+1}:new Object[]{after,limit+1});
        if(new HashSet<>(rows).size()!=rows.size())throw invalid();boolean more=rows.size()>limit;var selected=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);
        return new Page(selected,more?selected.getLast():null);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("temporary intent scan unavailable");}
}
