package com.lianxibu.site.repository.articles;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact current member/run raw fingerprints. Semantic source qualification remains an independent read. */
public final class JdbcCatalogVersionSourceFacts {
    public static final class CapacityRejected extends IllegalStateException{public CapacityRejected(){super("catalog version current source capacity unavailable");}}
    public record Text(long bytes,String hash){public Text{if(bytes<1||hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid();}}
    public record Snapshot(String category,String member,List<String> scalars,Text body,Text config){public Snapshot{scalars=Collections.unmodifiableList(new ArrayList<>(scalars));Objects.requireNonNull(body);Objects.requireNonNull(config);}@Override public String toString(){return "CatalogVersionCurrentSourceFacts[redacted]";}}
    private static final List<String> COLUMNS=List.of("i.id","i.run_id","r.id","i.source_id","r.source_id","i.revision","i.category","i.created_at","i.terminal_state","i.state","i.projection_decision","i.effective_valid","r.activation_stage","r.delivery_state","r.finished_at","r.config_version","i.entity_id","i.source_item_id","r.business_date");
    private final JdbcTemplate jdbc;private final String category;
    public JdbcCatalogVersionSourceFacts(DataSource source,String category){jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};}
    public Snapshot capture(String member){transaction(true);return read(member,false);}
    /** Both the exact member and its native source-run row remain locked until the original writer commits. */
    public Snapshot lockAndRead(String member){transaction(false);return read(member,true);}
    public Map<String,Snapshot> lockAndReadAll(Collection<String> members){transaction(false);return readMany(members,true);}
    private Snapshot read(String member,boolean locked){return readMany(List.of(member),locked).get(member);}
    private Map<String,Snapshot> readMany(Collection<String> members,boolean locked){
        Objects.requireNonNull(members);if(members.size()>100)throw new CapacityRejected();var expected=new TreeSet<String>();for(String member:members)if(!expected.add(WireValues.id(member,false)))throw invalid();if(expected.isEmpty())return Map.of();
        return jdbc.execute((ConnectionCallback<Map<String,Snapshot>>)connection->{
            boolean mysql=connection.getMetaData().getDatabaseProductName().equals("MySQL"),h2=connection.getMetaData().getDatabaseProductName().equals("H2");if(!mysql&&!h2)throw invalid();var columns=new ArrayList<>(COLUMNS);columns.add(mysql?"LOWER(HEX(i.evidence_hash))":"LOWER(RAWTOHEX(i.evidence_hash))");
            for(var entry:List.of(Map.entry("i.evidence_json",262144),Map.entry("r.config_snapshot_json",65536))){String field=entry.getKey(),hash=mysql?"LOWER(SHA2(CAST("+field+" AS BINARY),256))":"LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8("+field+"))))";columns.add("OCTET_LENGTH("+field+")");columns.add("CASE WHEN OCTET_LENGTH("+field+") BETWEEN 1 AND "+entry.getValue()+" THEN "+hash+" ELSE NULL END");}
            String placeholders=String.join(",",Collections.nCopies(expected.size(),"?"));String sql="SELECT "+String.join(",",columns)+" FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.id IN ("+placeholders+") ORDER BY i.id LIMIT "+(expected.size()+1)+(locked?" FOR UPDATE":"");
            try(var statement=connection.prepareStatement(sql)){org.springframework.jdbc.datasource.DataSourceUtils.applyTransactionTimeout(statement,Objects.requireNonNull(jdbc.getDataSource()));int index=1;for(String member:expected)statement.setString(index++,member);try(var result=statement.executeQuery()){
                var snapshots=new HashMap<String,Snapshot>();while(result.next()){if(snapshots.size()>=expected.size())throw invalid();var scalars=new ArrayList<String>();for(int n=1;n<=20;n++)scalars.add(result.getString(n));Long bodyBytes=result.getObject(21,Long.class),configBytes=result.getObject(23,Long.class);
                    if(bodyBytes==null||bodyBytes<1||bodyBytes>262144||configBytes==null||configBytes<1||configBytes>65536)throw new CapacityRejected();String member=scalars.getFirst();
                    if(!expected.contains(member)||!Objects.equals(scalars.get(1),scalars.get(2))||scalars.get(3)==null||!scalars.get(3).equals(scalars.get(4))||!category.equals(scalars.get(6))||scalars.get(19)==null||!scalars.get(19).matches("[0-9a-f]{64}"))throw invalid();
                    var snapshot=new Snapshot(category,member,scalars,new Text(bodyBytes,result.getString(22)),new Text(configBytes,result.getString(24)));if(snapshots.putIfAbsent(member,snapshot)!=null)throw invalid();
                }if(!snapshots.keySet().equals(expected))throw invalid();return Map.copyOf(snapshots);
            }}
        });
    }
    private static void transaction(boolean read){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=read||!Integer.valueOf(read?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog version current source facts unavailable");}
}
