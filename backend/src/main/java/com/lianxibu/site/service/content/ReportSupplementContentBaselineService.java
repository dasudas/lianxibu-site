package com.lianxibu.site.service.content;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.content.JdbcContentReads;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import com.lianxibu.site.domain.protocol.CanonicalJson;

/** Exact report target metadata for supplementary generation. Current body bytes verify its key only. */
public final class ReportSupplementContentBaselineService {
 public record Key(String id,Long revision,Long bodyRevision,String inputJobId,String bodyHash){
  @Override public String toString(){return "ReportTargetBaseline[redacted]";}
 }
 private final DataSource source;private final JdbcTemplate sql;private final ContentReadService contents;
 public ReportSupplementContentBaselineService(DataSource source,PlatformTransactionManager manager){
  this.source=Objects.requireNonNull(source);sql=new JdbcTemplate(source);contents=new ContentReadService(new JdbcContentReads(source),Objects.requireNonNull(manager),ContentReadService.denyUnverifiedReferences());
 }
 public Key read(String id,String type,LocalDate day){transaction(true);return verify(id,type,day,contents.comparisonVersion("report",id));}
 public Key applicationRead(String id,String type,LocalDate day){transaction(false);return verify(id,type,day,contents.provenApplicationVersion("report",id));}
 private Key verify(String id,String type,LocalDate day,Optional<ContentReadService.ComparisonVersion> proof){
  WireValues.id(id,false);if(day==null||type==null||!Set.of("daily","weekly","monthly").contains(type)||type.equals("weekly")&&day.getDayOfWeek()!=DayOfWeek.SUNDAY||type.equals("monthly")&&day.getDayOfMonth()!=day.lengthOfMonth())throw invalid();
  Long count=sql.queryForObject("SELECT COUNT(*) FROM contents WHERE id=?",Long.class,id);if(count==null||count>1||count<0||count==0&&!proof.isEmpty()||count==1&&proof.isEmpty())throw invalid();
  if(count==0)return new Key(id,null,null,null,null);
  var value=proof.orElseThrow();if(!value.proven()||!id.equals(value.id())||!"report".equals(value.kind())||value.bodyRevision()==null||value.bodyRevision()>value.revision()||value.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw invalid();
  JsonNode body=CanonicalJson.parse(value.bodyJson());LocalDate start=switch(type){case "daily"->day;case "weekly"->day.minusDays(6);case "monthly"->day.withDayOfMonth(1);default->throw invalid();};
  ZoneId zone=ZoneId.of("Asia/Shanghai");if(!type.equals(WireValues.text(body.get("type")))||!"Asia/Shanghai".equals(WireValues.text(body.get("timezone")))||!start.atStartOfDay(zone).toInstant().equals(WireValues.utcMicroseconds(body.get("period_start")))||!day.plusDays(1).atStartOfDay(zone).toInstant().equals(WireValues.utcMicroseconds(body.get("period_end"))))throw invalid();
  return new Key(id,value.revision(),value.bodyRevision(),value.inputJobId(),HexFormat.of().formatHex(value.bodyHash()));
 }
 private void transaction(boolean readonly){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readonly||!Integer.valueOf(readonly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||!TransactionSynchronizationManager.hasResource(source))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("native report content baseline unavailable");}
 @Override public String toString(){return "ReportSupplementContentBaselineService[redacted]";}
}
