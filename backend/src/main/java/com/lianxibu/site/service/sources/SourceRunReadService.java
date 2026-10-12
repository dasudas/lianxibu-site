package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcSourceRunReads;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.sources.SourcesService.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owner-only consistent run summaries. No prompts, evidence payloads or collector credentials. */
public final class SourceRunReadService {
    private final JdbcSourceRunReads store;
    private final TransactionTemplate reads;
    public SourceRunReadService(JdbcSourceRunReads store,PlatformTransactionManager tx) {
        this.store=Objects.requireNonNull(store);reads=new TransactionTemplate(tx);
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public RunPage listRuns(Actor actor,String source,LocalDate day,long page,int size) {
        owner(actor);paging(page,size);if(source!=null)WireValues.id(source,true);
        return read(()->{var rows=store.runs(source,day,page,size);return new RunPage(rows.rows().stream().map(SourceRunReadService::project).toList(),page,size,integer(rows.total()));});
    }
    public Run run(Actor actor,String id) {owner(actor);WireValues.id(id,false);return read(()->project(required(id)));}
    public RunItemPage listItems(Actor actor,String run,long page,int size) {
        owner(actor);WireValues.id(run,false);paging(page,size);
        return read(()->{required(run);var rows=store.items(run,page,size);return new RunItemPage(rows.rows().stream().map(SourceRunReadService::item).toList(),page,size,integer(rows.total()));});
    }
    private Map<String,Object> required(String id){var row=store.run(id);if(row==null)throw new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null);return row;}
    private <T>T read(Supplier<T> value){try{return reads.execute(s->value.get());}catch(IdentityService.Rejected known){throw known;}catch(RuntimeException invalid){throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}}
    private static Run project(Map<String,Object> r) {
        String result=nullable(r,"result_json"),resultHash=null;
        if(result!=null){var business=CanonicalJson.parse(result);if(!business.isObject())throw new IllegalStateException();var n=JsonMapper.shared().createObjectNode();n.put("operation","result");n.set("business",business);resultHash=CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,n);}
        byte[] hash=(byte[])r.get("manifest_hash");if(hash!=null&&hash.length!=32)throw new IllegalStateException();
        return new Run(id(r,"id",false),id(r,"source_id",true),choice(r,"run_kind","daily","probe","trial","production_qualification","historical_backfill","correction"),
            day(r,"business_date"),number(r,"generation"),time(r,"window_start",false),time(r,"window_end",false),
            choice(r,"fetch_mode","daily_delta","materialize"),choice(r,"collection_reason","daily","probe","trial","production_qualification","historical_backfill","correction"),
            number(r,"config_version"),number(r,"revision"),choice(r,"delivery_state","pending","claimed","receiving","waiting_items","complete","partial","no_usable_input","empty_success"),
            choice(r,"fetch_state","pending","running","succeeded","failed","partial"),choice(r,"analysis_state","waiting_input","queued","running","succeeded","partial","failed"),
            coverage(text(r,"coverage_json")),r.get("expected_count")==null?null:number(r,"expected_count"),number(r,"accepted_count"),number(r,"excluded_count"),number(r,"pending_count"),
            time(r,"barrier_closed_at",true),Objects.requireNonNull(bool(r.get("late_result"))),resultHash,hash==null?null:HexFormat.of().formatHex(hash));
    }
    private static RunItem item(Map<String,Object> r) {
        String sourceItem=nullable(r,"source_item_id"),reason=nullable(r,"exclusion_reason"),entity=nullable(r,"entity_id");
        if(sourceItem!=null&&(sourceItem.isEmpty()||sourceItem.codePointCount(0,sourceItem.length())>4096))throw new IllegalStateException();
        if(reason!=null&&!reason.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalStateException();
        return new RunItem(id(r,"id",false),id(r,"run_id",false),id(r,"member_id",false),sourceItem,
            optionalChoice(r,"category","news_tech","agent","skill","workflow","model"),entity==null?null:WireValues.id(entity,false),number(r,"revision"),
            choice(r,"terminal_state","pending","accepted","excluded"),choice(r,"state","pending","needs_review","ready","analyzed","excluded","failed"),
            bool(r.get("is_new")),bool(r.get("high_value")),optionalChoice(r,"projection_decision","initialized","unchanged","promoted","historical","needs_review"),reason);
    }
    private static Coverage coverage(String raw) {
        var n=CanonicalJson.parse(raw);if(n.isObject()&&n.isEmpty())return new Coverage("no_input",List.of(),List.of(),List.of());
        var keys=Set.of("state","missing_inputs","missing_dates","no_update_dates");WireValues.object(n,keys,keys);
        String state=WireValues.text(n.get("state"));if(!Set.of("complete","partial","no_input").contains(state))throw new IllegalStateException();
        var missing=new ArrayList<MissingInput>();if(!n.get("missing_inputs").isArray()||n.get("missing_inputs").size()>1000)throw new IllegalStateException();
        for(var m:n.get("missing_inputs")){var fields=Set.of("kind","id","business_date","reason");WireValues.object(m,fields,fields);
            missing.add(new MissingInput(bounded(m.get("kind"),32),m.get("id").isNull()?null:WireValues.id(m.get("id"),false),
                m.get("business_date").isNull()?null:WireValues.businessDate(m.get("business_date")),bounded(m.get("reason"),256)));}
        return new Coverage(state,List.copyOf(missing),dates(n.get("missing_dates")),dates(n.get("no_update_dates")));
    }
    private static String bounded(JsonNode n,int max){String v=WireValues.text(n);if(v.isEmpty()||v.codePointCount(0,v.length())>max)throw new IllegalStateException();return v;}
    private static List<LocalDate> dates(JsonNode n){if(!n.isArray()||n.size()>366)throw new IllegalStateException();var out=new ArrayList<LocalDate>();for(var d:n)out.add(WireValues.businessDate(d));return List.copyOf(out);}
    private static String text(Map<String,Object> r,String key){String v=nullable(r,key);if(v==null)throw new IllegalStateException();return v;}
    private static String nullable(Map<String,Object> r,String key){var v=r.get(key);if(v==null)return null;if(v instanceof java.sql.Clob c){try{return c.getSubString(1,Math.toIntExact(c.length()));}catch(java.sql.SQLException e){throw new IllegalStateException();}}if(!(v instanceof String))throw new IllegalStateException();return (String)v;}
    private static String id(Map<String,Object> r,String k,boolean source){return WireValues.id(text(r,k),source);}
    private static String choice(Map<String,Object> r,String k,String... choices){String v=text(r,k);if(!Set.of(choices).contains(v))throw new IllegalStateException();return v;}
    private static String optionalChoice(Map<String,Object> r,String k,String... choices){return r.get(k)==null?null:choice(r,k,choices);}
    private static long integer(long n){if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalStateException();return n;}
    private static long number(Map<String,Object> r,String k){if(!(r.get(k) instanceof Number n))throw new IllegalStateException();return integer(new java.math.BigDecimal(n.toString()).longValueExact());}
    private static LocalDate day(Map<String,Object> r,String k){var v=r.get(k);if(v instanceof java.sql.Date d)return d.toLocalDate();if(v instanceof LocalDate d)return d;throw new IllegalStateException();}
    private static Instant time(Map<String,Object> r,String k,boolean optional){var v=r.get(k);if(v==null&&optional)return null;if(v instanceof java.sql.Timestamp t)return t.toInstant();if(v instanceof java.time.LocalDateTime t)return t.toInstant(java.time.ZoneOffset.UTC);throw new IllegalStateException();}
    private static Boolean bool(Object v){if(v==null)return null;if(v instanceof Boolean b)return b;if(v instanceof Number n&&(n.intValue()==0||n.intValue()==1))return n.intValue()==1;throw new IllegalStateException();}
    private static void paging(long page,int size){if(page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid page");}
    private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
}
