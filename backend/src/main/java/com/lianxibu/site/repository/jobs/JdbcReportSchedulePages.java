package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.WireValues;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Bounded complete pagination of original initial scheduling locators. A locator grants no source or no-update authority. */
public final class JdbcReportSchedulePages {
 public record Locator(String id,String type,String logicalKey,LocalDate day){@Override public String toString(){return "OriginalReportScheduleLocator[redacted]";}}
 public record Page(List<Locator> rows,String nextCursor,boolean hasMore){public Page{rows=List.copyOf(rows);}@Override public String toString(){return "OriginalReportSchedulePage[redacted]";}}
 private final JdbcTemplate jdbc;
 public JdbcReportSchedulePages(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
 public Page read(String after,int size){readonly();if(size<1||size>64)throw invalid();if(after!=null)WireValues.id(after,false);
  var args=new ArrayList<Object>();if(after!=null)args.add(after);args.add(size+1);
  var rows=jdbc.query("SELECT id,task_type,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 96 THEN logical_key ELSE NULL END,business_date,logical_key_hash FROM jobs WHERE record_kind='generation_intent' AND generation=1 AND task_type IN ('source_day_roster','daily_report','weekly_report','monthly_report')"+(after==null?"":" AND id>?")+" ORDER BY id LIMIT ?",(r,n)->{
   String id=WireValues.id(r.getString(1),false),type=r.getString(2),key=r.getString(3);LocalDate day;try{day=LocalDate.parse(r.getString(4));}catch(RuntimeException missing){throw invalid();}
   String expected=switch(type){case "source_day_roster"->"source-day-roster:"+day;case "daily_report"->"report:daily:"+day;case "weekly_report"->{if(day.getDayOfWeek()!=DayOfWeek.SUNDAY)throw invalid();yield "report:weekly:"+day;}case "monthly_report"->{if(day.getDayOfMonth()!=day.lengthOfMonth())throw invalid();yield "report:monthly:"+day;}default->throw invalid();};
   String stable="source_day_roster".equals(type)?JdbcSourceDayRosters.owner(day):JdbcFrozenInputs.stableId("intent",expected+":1");
   if(!expected.equals(key)||!id.equals(stable)||!Arrays.equals(r.getBytes(5),JdbcGenerationLineage.hash(expected)))throw invalid();return new Locator(id,type,key,day);
  },args.toArray());
  if(rows.size()>size+1||new HashSet<>(rows.stream().map(Locator::id).toList()).size()!=rows.size())throw invalid();boolean more=rows.size()>size;var selected=List.copyOf(rows.subList(0,Math.min(size,rows.size())));return new Page(selected,selected.isEmpty()?after:selected.getLast().id(),more);
 }
 private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()==false||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("original report scheduling locators unavailable");}
}
