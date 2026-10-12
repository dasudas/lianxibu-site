package com.lianxibu.site.service.jobs;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Binds the original native whole-day INPUT authority to one exact result application mode. */
public final class CatalogDayAnalysisResultPermitService {
    public static final class Captured {
        private final Object issuer;private final ResultApplicationService.Input input;
        private final CatalogDayAnalysisDispatchPermitService.Captured original;private final boolean manual;
        private Captured(Object issuer,ResultApplicationService.Input input,CatalogDayAnalysisDispatchPermitService.Captured original,boolean manual){this.issuer=issuer;this.input=input;this.original=original;this.manual=manual;}
        @Override public String toString(){return "OriginalCatalogAnalysisResultPermit[redacted]";}
    }
    private final Object issuer=new Object();private final CatalogDayAnalysisDispatchPermitService originals;private final String category;
    public CatalogDayAnalysisResultPermitService(CatalogDayAnalysisDispatchPermitService originals,String category){this.originals=Objects.requireNonNull(originals);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};}
    public Captured capture(ResultApplicationService.Input input,boolean manual){
        if(TransactionSynchronizationManager.isActualTransactionActive()||input==null||input.stage()==null||!input.stage().analysis()||!input.stage().name().toLowerCase(Locale.ROOT).equals(category+"_analysis"))throw invalid();
        var original=originals.capture(input.rootId());if(original==null||!original.matches(input))throw invalid();return new Captured(issuer,input,original,manual);
    }
    public void verify(Captured captured,ResultApplicationService.Input input,boolean manual){
        if(captured==null||captured.issuer!=issuer||captured.manual!=manual||!captured.input.equals(input)||!originals.verifyForApplication(captured.original,manual))throw invalid();
    }
    private static ResultApplicationService.Rejected invalid(){return new ResultApplicationService.Rejected();}
}
