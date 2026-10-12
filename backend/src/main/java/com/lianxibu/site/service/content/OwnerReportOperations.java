package com.lianxibu.site.service.content;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.OwnerReportInitialService;
import com.lianxibu.site.service.jobs.OwnerReportSupplementService;
import java.util.Objects;

/** Content-module adapter for the two actual owner report command services. */
public final class OwnerReportOperations {
 private final OwnerReportInitialService initial;private final OwnerReportSupplementService supplement;
 public OwnerReportOperations(OwnerReportInitialService initial,OwnerReportSupplementService supplement){this.initial=Objects.requireNonNull(initial);this.supplement=Objects.requireNonNull(supplement);}
 public ContentService.Job generationIntent(Actor actor,ContentService.GenerationIntentWrite request,String key,String csrf,String origin){return request!=null&&"initial".equals(request.mode())?initial.generationIntent(actor,request,key,csrf,origin):supplement.generationIntent(actor,request,key,csrf,origin);}
 public ContentService.ActionResult supplement(Actor actor,String kind,String id,ContentService.ContentAction request,String key,String csrf,String origin){return supplement.action(actor,kind,id,request,key,csrf,origin);}
 @Override public String toString(){return "OwnerReportOperations[redacted]";}
}
