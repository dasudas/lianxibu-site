package com.lianxibu.site.repository.sources;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;

/** Selects only the owner contract fields. Raw evidence, configuration and lease credentials stay unread. */
public final class JdbcSourceRunReads {
    public record Page(List<Map<String,Object>> rows,long total) { }
    private static final String RUN = "r.id,r.source_id,r.run_kind,r.business_date,r.generation,r.window_start,r.window_end,"
        +"r.fetch_mode,r.collection_reason,r.config_version,r.revision,r.delivery_state,r.fetch_state,r.analysis_state,"
        +"r.coverage_json,r.expected_count,r.accepted_count,r.excluded_count,r.pending_count,r.barrier_closed_at,"
        +"r.result_json,r.manifest_hash,EXISTS(SELECT 1 FROM source_run_items i WHERE i.run_id=r.id"
        +" AND i.applied_at>r.barrier_closed_at) AS late_result";
    private static final ColumnMapRowMapper RUN_MAPPER=new ColumnMapRowMapper(){
        @Override protected Object getColumnValue(java.sql.ResultSet row,int column)throws java.sql.SQLException{
            String name=row.getMetaData().getColumnLabel(column).toLowerCase(java.util.Locale.ROOT);
            if(name.equals("business_date")){String value=row.getString(column);return value==null?null:LocalDate.parse(value);}
            if(java.util.Set.of("window_start","window_end","barrier_closed_at").contains(name))return row.getTimestamp(column,java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
            return super.getColumnValue(row,column);
        }
    };
    private final JdbcTemplate jdbc;
    public JdbcSourceRunReads(DataSource source) { jdbc=new JdbcTemplate(source); }
    public Map<String,Object> run(String id) {
        var rows=jdbc.query("SELECT "+RUN+" FROM source_runs r WHERE r.id=?",RUN_MAPPER,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Page runs(String sourceId,LocalDate day,long page,int size) {
        String filter=" WHERE 1=1";var args=new ArrayList<Object>();
        if(sourceId!=null){filter+=" AND r.source_id=?";args.add(sourceId);}
        if(day!=null){filter+=" AND r.business_date=?";args.add(day.toString());}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM source_runs r"+filter,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact(page-1,size));
        return new Page(jdbc.query("SELECT "+RUN+" FROM source_runs r"+filter
            +" ORDER BY r.business_date DESC,r.id LIMIT ? OFFSET ?",RUN_MAPPER,args.toArray()),total);
    }
    public Page items(String run,long page,int size) {
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE run_id=?",Long.class,run);
        var rows=jdbc.queryForList("SELECT id,run_id,member_id,source_item_id,category,entity_id,revision,terminal_state,state,"
            +"is_new,high_value,projection_decision,exclusion_reason FROM source_run_items WHERE run_id=? ORDER BY id LIMIT ? OFFSET ?",
            run,size,Math.multiplyExact(page-1,size));
        return new Page(rows,total);
    }
}
