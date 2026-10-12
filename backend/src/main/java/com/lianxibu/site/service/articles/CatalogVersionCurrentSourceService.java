package com.lianxibu.site.service.articles;
import com.lianxibu.site.repository.articles.JdbcCatalogVersionSourceFacts;
import java.util.*;
import javax.sql.DataSource;

/** Source-module facade for exact bounded native fingerprints; no independent transaction or execution authority. */
public final class CatalogVersionCurrentSourceService {
    public static final class CapacityRejected extends IllegalStateException{public CapacityRejected(){super("catalog version current source capacity unavailable");}}
    public record Text(long bytes,String hash){ }
    public record Snapshot(String category,String member,List<String> scalars,Text body,Text config){public Snapshot{scalars=Collections.unmodifiableList(new ArrayList<>(scalars));Objects.requireNonNull(body);Objects.requireNonNull(config);}@Override public String toString(){return "CatalogVersionCurrentSourceSnapshot[redacted]";}}
    private final JdbcCatalogVersionSourceFacts storage;
    public CatalogVersionCurrentSourceService(DataSource source,String category){storage=new JdbcCatalogVersionSourceFacts(Objects.requireNonNull(source),category);}
    public Snapshot capture(String member){try{return snapshot(storage.capture(member));}catch(JdbcCatalogVersionSourceFacts.CapacityRejected guarded){throw new CapacityRejected();}}
    public Snapshot lockAndRead(String member){try{return snapshot(storage.lockAndRead(member));}catch(JdbcCatalogVersionSourceFacts.CapacityRejected guarded){throw new CapacityRejected();}}
    public Map<String,Snapshot> lockAndReadAll(Collection<String> members){try{var values=new HashMap<String,Snapshot>();storage.lockAndReadAll(members).forEach((id,value)->values.put(id,snapshot(value)));return Map.copyOf(values);}catch(JdbcCatalogVersionSourceFacts.CapacityRejected guarded){throw new CapacityRejected();}}
    private static Snapshot snapshot(JdbcCatalogVersionSourceFacts.Snapshot value){return new Snapshot(value.category(),value.member(),value.scalars(),new Text(value.body().bytes(),value.body().hash()),new Text(value.config().bytes(),value.config().hash()));}
}
