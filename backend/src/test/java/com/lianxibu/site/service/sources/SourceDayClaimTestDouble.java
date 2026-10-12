package com.lianxibu.site.service.sources;

import static org.mockito.Mockito.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Existing lease/HTTP fixtures use explicit day-proof doubles; actual admission has its own tests. */
public final class SourceDayClaimTestDouble {
    public static SourceDayRunClaimAdmissionService allow(DataSource source){
        var guard=mock(SourceDayRunClaimAdmissionService.class);var sql=new JdbcTemplate(source);
        when(guard.prepare(anySet(),anySet())).thenAnswer(call->{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("fixture day preparation belongs outside writer");
            Set<String> requested=call.getArgument(1);var ids=sql.query("SELECT id,source_id FROM source_runs WHERE run_kind='daily'",(rs,index)->requested.contains(rs.getString(2))?rs.getString(1):null).stream().filter(Objects::nonNull).sorted().toList();
            var prepared=mock(SourceDayRunClaimAdmissionService.Prepared.class);when(prepared.runIds()).thenReturn(ids);return prepared;
        });
        when(guard.allows(any(),anyString())).thenAnswer(call->{if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new AssertionError("fixture day proof belongs inside writer");return true;});return guard;
    }
    private SourceDayClaimTestDouble(){ }
}
