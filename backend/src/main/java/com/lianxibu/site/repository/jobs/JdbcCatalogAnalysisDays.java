package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Bounded scheduling locators. Full original cohort and member proofs must be read by the consumer. */
public final class JdbcCatalogAnalysisDays {
    public record Day(LocalDate day,String ownerId){@Override public String toString(){return "CatalogAnalysisDayLocator[redacted]";}}
    public record Page(List<Day> days,boolean hasMore,LocalDate nextCursor){
        public Page{days=List.copyOf(days);if(hasMore!=(nextCursor!=null))throw invalid();}
        @Override public String toString(){return "CatalogAnalysisDayPage[redacted]";}
    }
    private final JdbcTemplate sql;
    public JdbcCatalogAnalysisDays(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Page page(LocalDate after,LocalDate through,int limit){
        readonly();Objects.requireNonNull(through);if(limit<1||limit>64||after!=null&&!after.isBefore(through))throw invalid();
        String query="SELECT id,business_date,CASE WHEN OCTET_LENGTH(logical_key)<=128 THEN logical_key ELSE NULL END,logical_key_hash,generation,state,input_state,revision,call_count,max_attempts,priority_no,record_identity_hash FROM jobs WHERE record_kind='generation_intent' AND task_type='source_day_roster' AND business_date<=?"+(after==null?"":" AND business_date>?")+" ORDER BY business_date,id LIMIT ?";
        var parameters=new ArrayList<Object>();parameters.add(through.toString());if(after!=null)parameters.add(after.toString());parameters.add(limit+1);
        var values=sql.query(query,(rs,n)->{
            String date=rs.getString(2);LocalDate day=LocalDate.parse(Objects.requireNonNull(date));String logical="source-day-roster:"+day;
            var identity=JsonMapper.shared().createObjectNode().put("record_kind","generation_intent").put("natural_key",logical+"\u0000"+1);
            if(!date.equals(day.toString())||!JdbcSourceDayRosters.owner(day).equals(rs.getString(1))||!logical.equals(rs.getString(3))||!same(sha(logical),rs.getBytes(4))||number(rs.getObject(5))!=1||!"waiting_input".equals(rs.getString(6))||!"waiting_input".equals(rs.getString(7))||number(rs.getObject(8))!=0||number(rs.getObject(9))!=0||rs.getObject(10)!=null||number(rs.getObject(11))!=0||!same(HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,identity)),rs.getBytes(12)))throw invalid();
            return new Day(day,rs.getString(1));
        },parameters.toArray());
        LocalDate last=after;for(var value:values){if(value.day().isAfter(through)||last!=null&&!value.day().isAfter(last))throw invalid();last=value.day();}
        boolean more=values.size()>limit;var result=List.copyOf(values.subList(0,Math.min(limit,values.size())));return new Page(result,more,more?result.getLast().day():null);
    }
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{return new java.math.BigInteger(value.toString()).longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static boolean same(byte[] left,byte[] right){return right!=null&&MessageDigest.isEqual(left,right);}
    private static void readonly(){Integer level=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||level==null||level!=TransactionDefinition.ISOLATION_READ_COMMITTED&&level!=TransactionDefinition.ISOLATION_REPEATABLE_READ)throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day locator unavailable");}
}
