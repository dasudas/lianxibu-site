package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;

/** Current bounded native replay fingerprints. They grant no final business completion authority. */
public final class JdbcCatalogSourceFinalCommitFacts {
 public static final class CapacityRejected extends IllegalStateException{public CapacityRejected(){super("catalog final native replay capacity unavailable");}}
 public record Text(long bytes,String hash){public Text{if(bytes< -1||bytes==-1&&hash!=null||bytes>=0&&(hash==null||!hash.matches("[0-9a-f]{64}")))throw invalid();}}
 public record Row(List<String> scalars,List<Text> texts){public Row{scalars=Collections.unmodifiableList(new ArrayList<>(scalars));texts=List.copyOf(texts);}@Override public String toString(){return "CatalogFinalNativeFactRow[redacted]";}}
 public record Snapshot(String category,String owner,List<String> roots,List<Row> rows){public Snapshot{roots=List.copyOf(roots);rows=List.copyOf(rows);}@Override public String toString(){return "CatalogFinalNativeFacts[redacted]";}}
 private static final int MAX_ROWS=4096;private static final long MAX_TOTAL_BYTES=128L*1024*1024;
 private static final List<String> SCALARS=List.of("id","record_kind","record_identity_hash","task_type","owner_job_id","purpose","stamp_id","part_no","intent_id","root_job_id","attempt_no","generation","stage_part","state","revision","call_count","max_attempts","input_state","input_hash","payload_hash","action_payload_hash","action_key_hash","target_key_hash","logical_key_hash","result_hash","business_input_hash","snapshot_owner_id","target_type","operator_id","business_date","input_cutoff_at","applied_at","acted_at","updated_at","created_at");
 private static final Set<String> BINARY=Set.of("record_identity_hash","input_hash","payload_hash","action_payload_hash","action_key_hash","target_key_hash","logical_key_hash","result_hash","business_input_hash");
 private static final List<String> TEXTS=List.of("payload_json","action_payload","target_key_json","input_snapshot_json","request_json","result_json","logical_key","reason");
 private static final int[] LIMITS={524288,524288,4096,65536,1048576,4096,4096,8192};
 private final JdbcTemplate jdbc;private final String category,type;
 public JdbcCatalogSourceFinalCommitFacts(DataSource source,String category){jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};type=category+"_dedup";}
 public Snapshot capture(String owner,List<String> roots){JdbcSourceDedupResults.requireReadOnly();return read(owner,roots,false);}
 /** The caller retains the native locks until its writer transaction commits. */
 public Snapshot lockAndRead(String owner,List<String> roots){JdbcFrozenInputs.requireTx();return read(owner,roots,true);}
 private Snapshot read(String owner,List<String> input,boolean locked){
  try{
   if(owner==null||!owner.matches("[0-9a-f]{64}")||input==null)throw invalid();if(input.size()>100)throw new CapacityRejected();var sorted=new TreeSet<String>();for(String root:input)if(root==null||!root.matches("[0-9a-f]{64}")||root.equals(owner)||!sorted.add(root))throw invalid();var roots=List.copyOf(sorted);owner(owner,locked);
   return jdbc.execute((ConnectionCallback<Snapshot>)connection->{String product=connection.getMetaData().getDatabaseProductName();boolean mysql=product.equals("MySQL"),h2=product.equals("H2");if(!mysql&&!h2)throw invalid();
    var columns=new ArrayList<String>();for(String scalar:SCALARS)columns.add(BINARY.contains(scalar)?(mysql?"LOWER(HEX("+scalar+"))":"LOWER(RAWTOHEX("+scalar+"))"):scalar);
    for(int n=0;n<TEXTS.size();n++){String name=TEXTS.get(n),hash=mysql?"LOWER(SHA2(CAST("+name+" AS BINARY),256))":"LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8("+name+"))))";columns.add("OCTET_LENGTH("+name+")");columns.add("CASE WHEN OCTET_LENGTH("+name+") <= "+LIMITS[n]+" THEN "+hash+" ELSE NULL END");}
    var owned=new ArrayList<String>();owned.add(owner);owned.addAll(roots);var hashes=new ArrayList<byte[]>();for(String id:owned)hashes.add(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind",id.equals(owner)?"generation_intent":"logical_task").put("id",id))));
    String ownerMarks=String.join(",",Collections.nCopies(owned.size(),"?")),rootMarks=String.join(",",Collections.nCopies(roots.size(),"?"));String scope="id=? OR intent_id=? OR owner_job_id IN ("+ownerMarks+") OR target_key_hash IN ("+ownerMarks+")";if(!roots.isEmpty())scope+=" OR root_job_id IN ("+rootMarks+")";
    String sql="SELECT "+String.join(",",columns)+" FROM jobs WHERE "+scope+" ORDER BY id LIMIT "+(MAX_ROWS+1)+(locked?" FOR UPDATE":"");var rows=new ArrayList<Row>();long total=0;
    try(var statement=connection.prepareStatement(sql)){org.springframework.jdbc.datasource.DataSourceUtils.applyTransactionTimeout(statement,Objects.requireNonNull(jdbc.getDataSource()));int index=1;statement.setString(index++,owner);statement.setString(index++,owner);for(String id:owned)statement.setString(index++,id);for(byte[] hash:hashes)statement.setBytes(index++,hash);for(String root:roots)statement.setString(index++,root);
     try(var result=statement.executeQuery()){while(result.next()){if(rows.size()>=MAX_ROWS)throw new CapacityRejected();var scalars=new ArrayList<String>();for(int n=0;n<SCALARS.size();n++)scalars.add(result.getString(n+1));var texts=new ArrayList<Text>();for(int n=0;n<TEXTS.size();n++){Long length=result.getObject(SCALARS.size()+1+n*2,Long.class);if(length!=null&&(length<0||length>LIMITS[n]||(total+=length)>MAX_TOTAL_BYTES))throw new CapacityRejected();texts.add(new Text(length==null?-1:length,result.getString(SCALARS.size()+2+n*2)));}rows.add(new Row(scalars,texts));}}
    }
    if(rows.isEmpty()||rows.stream().noneMatch(row->owner.equals(row.scalars().getFirst())))throw invalid();for(var row:rows)if("logical_task".equals(row.scalars().get(1))&&owner.equals(row.scalars().get(8))&&!roots.contains(row.scalars().getFirst()))throw invalid();for(String root:roots)if(rows.stream().noneMatch(row->root.equals(row.scalars().getFirst())&&"logical_task".equals(row.scalars().get(1))&&type.equals(row.scalars().get(3))&&owner.equals(row.scalars().get(8))))throw invalid();return new Snapshot(category,owner,roots,rows);
   });
  }catch(CapacityRejected guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
 }
 private void owner(String owner,boolean locked){
  var rows=jdbc.query("SELECT id,record_kind,record_identity_hash,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,generation,task_type,business_date FROM jobs WHERE id=? LIMIT 2"+(locked?" FOR UPDATE":""),(r,n)->{
   String key=r.getString(4),day=r.getString(8);Long generation=r.getObject(6,Long.class);if(!owner.equals(r.getString(1))||!"generation_intent".equals(r.getString(2))||key==null||key.isBlank()||generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||!type.equals(r.getString(7))||day==null||!owner.equals(JdbcFrozenInputs.stableId("intent",key+":"+generation))||!Arrays.equals(r.getBytes(5),JdbcGenerationLineage.hash(key)))throw invalid();LocalDate.parse(day);var natural=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("record_kind","generation_intent").put("natural_key",key+"\u0000"+generation);if(!Arrays.equals(r.getBytes(3),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,natural))))throw invalid();return owner;
  },owner);if(rows.size()!=1)throw invalid();
 }
 private static IllegalStateException invalid(){return new IllegalStateException("catalog final native replay facts unavailable");}
}
