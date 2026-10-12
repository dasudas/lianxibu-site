package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete bounded original analysis job fingerprints. They do not confer source or business writer authority. */
public final class JdbcCatalogVersionJobFacts {
    public static final int MAX_ROWS=256;
    public static final long MAX_TOTAL_BYTES=16L*1024*1024;
    public static final class CapacityRejected extends IllegalStateException{public CapacityRejected(){super("catalog version original job capacity unavailable");}}
    public record Text(long bytes,String hash){public Text{if(bytes< -1||bytes==-1&&hash!=null||bytes>=0&&(hash==null||!hash.matches("[0-9a-f]{64}")))throw invalid();}}
    public record Row(List<String> scalars,List<Text> texts){public Row{scalars=Collections.unmodifiableList(new ArrayList<>(scalars));texts=List.copyOf(texts);}@Override public String toString(){return "CatalogVersionOriginalJobRow[redacted]";}}
    public record Snapshot(String category,String root,String intent,String claimTargetHash,List<Row> rows){public Snapshot{rows=List.copyOf(rows);}@Override public String toString(){return "CatalogVersionOriginalJobFacts[redacted]";}}
    private static final List<String> SCALARS=List.of("id","record_kind","record_identity_hash","task_type","owner_job_id","purpose","stamp_id","part_no","intent_id","root_job_id","attempt_no","generation","stage_part","state","revision","call_count","max_attempts","input_state","input_hash","payload_hash","action_payload_hash","action_key_hash","target_key_hash","logical_key_hash","result_hash","business_input_hash","snapshot_owner_id","target_type","operator_id","business_date","input_cutoff_at","applied_at","acted_at","updated_at","created_at","dispatch_token","worker_instance_id","request_deadline","send_authorized_at");
    private static final Set<String> BINARY=Set.of("record_identity_hash","input_hash","payload_hash","action_payload_hash","action_key_hash","target_key_hash","logical_key_hash","result_hash","business_input_hash");
    private static final List<String> TEXTS=List.of("payload_json","action_payload","target_key_json","input_snapshot_json","request_json","result_json","logical_key","reason");
    private static final int[] LIMITS={524288,3145728,16384,65536,1048576,16384,4096,8192};
    private final JdbcTemplate jdbc;private final String category,type;
    public JdbcCatalogVersionJobFacts(DataSource source,String category){jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};type=category+"_analysis";}
    public Snapshot capture(String root,String claimTargetHash){transaction(true);return read(root,claimTargetHash,false);}
    /** Holds every exact original proof row until the caller's READ_COMMITTED writer transaction commits. */
    public Snapshot lockAndRead(String root,String claimTargetHash){transaction(false);return read(root,claimTargetHash,true);}
    private Snapshot read(String root,String claimTargetHash,boolean locked){
        WireValues.id(root,false);if(claimTargetHash==null||!claimTargetHash.matches("[0-9a-f]{64}"))throw invalid();
        var headers=jdbc.query("SELECT id,record_kind,task_type,intent_id,logical_key_hash,generation FROM jobs WHERE id=? LIMIT 2"+(locked?" FOR UPDATE":""),(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getObject(6,Long.class)},root);
        if(headers.size()!=1)throw invalid();var header=headers.getFirst();if(!root.equals(header[0])||!"logical_task".equals(header[1])||!type.equals(header[2])||header[3]==null||header[4]==null||((byte[])header[4]).length!=32||header[5]==null||(long)header[5]<1||(long)header[5]>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();String intent=WireValues.id((String)header[3],false);
        var intents=jdbc.query("SELECT id,record_kind,task_type,logical_key_hash,generation FROM jobs WHERE id=? LIMIT 2"+(locked?" FOR UPDATE":""),(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getBytes(4),r.getObject(5,Long.class)},intent);
        if(intents.size()!=1||!intent.equals(intents.getFirst()[0])||!"generation_intent".equals(intents.getFirst()[1])||!type.equals(intents.getFirst()[2])||!Arrays.equals((byte[])header[4],(byte[])intents.getFirst()[3])||!header[5].equals(intents.getFirst()[4]))throw invalid();
        return jdbc.execute((ConnectionCallback<Snapshot>)connection->{
            boolean mysql=connection.getMetaData().getDatabaseProductName().equals("MySQL"),h2=connection.getMetaData().getDatabaseProductName().equals("H2");if(!mysql&&!h2)throw invalid();var columns=new ArrayList<String>();
            for(String field:SCALARS)columns.add(BINARY.contains(field)?(mysql?"LOWER(HEX("+field+"))":"LOWER(RAWTOHEX("+field+"))"):field);
            for(int n=0;n<TEXTS.size();n++){String field=TEXTS.get(n),hash=mysql?"LOWER(SHA2(CAST("+field+" AS BINARY),256))":"LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8("+field+"))))";columns.add("OCTET_LENGTH("+field+")");columns.add("CASE WHEN OCTET_LENGTH("+field+") <= "+LIMITS[n]+" THEN "+hash+" ELSE NULL END");}
            String sql="SELECT "+String.join(",",columns)+" FROM jobs WHERE id=? OR id=? OR intent_id=? OR root_job_id=? OR owner_job_id=? OR snapshot_owner_id=? OR target_key_hash IN (?,?) OR (record_kind='generation_intent' AND logical_key_hash=?) ORDER BY id LIMIT "+(MAX_ROWS+1)+(locked?" FOR UPDATE":"");var rows=new ArrayList<Row>();long total=0;
            try(var statement=connection.prepareStatement(sql)){org.springframework.jdbc.datasource.DataSourceUtils.applyTransactionTimeout(statement,Objects.requireNonNull(jdbc.getDataSource()));for(int n=1;n<=6;n++)statement.setString(n,n==2||n==3?intent:root);statement.setBytes(7,HexFormat.of().parseHex(claimTargetHash));statement.setBytes(8,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root))));statement.setBytes(9,(byte[])header[4]);
                try(var result=statement.executeQuery()){while(result.next()){if(rows.size()>=MAX_ROWS)throw new CapacityRejected();var scalars=new ArrayList<String>();for(int n=0;n<SCALARS.size();n++)scalars.add(result.getString(n+1));var texts=new ArrayList<Text>();for(int n=0;n<TEXTS.size();n++){Long length=result.getObject(SCALARS.size()+1+n*2,Long.class);if(length!=null&&(length<0||length>LIMITS[n]||(total+=length)>MAX_TOTAL_BYTES))throw new CapacityRejected();texts.add(new Text(length==null?-1:length,result.getString(SCALARS.size()+2+n*2)));}rows.add(new Row(scalars,texts));}}
            }
            if(rows.stream().noneMatch(row->root.equals(row.scalars().getFirst()))||rows.stream().noneMatch(row->intent.equals(row.scalars().getFirst()))||rows.stream().noneMatch(row->"disposition".equals(row.scalars().get(1))&&"worker_claim".equals(row.scalars().get(27))&&claimTargetHash.equals(row.scalars().get(22))))throw invalid();return new Snapshot(category,root,intent,claimTargetHash,rows);
        });
    }
    private static void transaction(boolean read){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=read||!Integer.valueOf(read?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog version original job facts unavailable");}
}
