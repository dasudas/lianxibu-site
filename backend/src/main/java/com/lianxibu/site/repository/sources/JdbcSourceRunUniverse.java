package com.lianxibu.site.repository.sources;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded original manifest reads under the shared writer; no source or audit row is changed. */
public final class JdbcSourceRunUniverse {
    public record Metadata(String id,Long parts,String result) { }
    public record Receipt(String runId,String scope,String json,byte[] businessHash) { }
    public record Member(String id,String runId,String sourceId,String memberId,String sourceItemId,String observationId,
                         byte[] payloadHash,String replacesObservationId,long partNo,String terminal) { }
    private final JdbcTemplate jdbc;
    public JdbcSourceRunUniverse(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Metadata metadata(String runId) {
        writer();var rows=jdbc.query("SELECT id,part_count,OCTET_LENGTH(result_json),result_json FROM source_runs WHERE id=? FOR UPDATE",(rs,n)->{
            if(rs.getObject(3)==null||rs.getLong(3)>65536)throw invalid();
            return new Metadata(rs.getString(1),rs.getObject(2)==null?null:rs.getLong(2),rs.getString(4));
        },runId);if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    public void receipts(String runId,Consumer<Receipt> consumer) {
        writer();Objects.requireNonNull(consumer);long[] size={0,0};
        jdbc.query(connection->{
            var statement=connection.prepareStatement("SELECT run_id,scope,OCTET_LENGTH(ack_json),ack_json,business_hash FROM request_receipts"
                    +" WHERE run_id=? AND receipt_kind='collector_control' AND control_operation='manifest' LIMIT 10001 FOR UPDATE",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
            statement.setString(1,runId);
            statement.setFetchSize("MySQL".equals(connection.getMetaData().getDatabaseProductName())?Integer.MIN_VALUE:100);
            return statement;
        },(RowCallbackHandler)rs->{
            if(++size[0]>10000||rs.getObject(3)==null||rs.getLong(3)>262144||rs.getLong(3)<1|| (size[1]+=rs.getLong(3))>64L*1024*1024)throw invalid();
            consumer.accept(new Receipt(rs.getString(1),rs.getString(2),rs.getString(4),rs.getBytes(5)));
        });
    }
    public List<Member> members(String runId) {
        writer();var rows=jdbc.query("SELECT id,run_id,source_id,member_id,source_item_id,observation_id,payload_hash,replaces_observation_id,part_no,terminal_state"
                +" FROM source_run_items WHERE run_id=? AND part_no IS NOT NULL LIMIT 10001 FOR UPDATE",(rs,n)->new Member(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getBytes(7),rs.getString(8),rs.getLong(9),rs.getString(10)),runId);
        if(rows.size()>10000)throw invalid();return rows;
    }
    private static void writer(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("manifest universe requires shared writer transaction");}
    private static IllegalStateException invalid(){return new IllegalStateException("original manifest storage unavailable");}
}
