package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.OperationalAdmissionService;
import com.lianxibu.site.service.jobs.OperationalAdmissionService.Operation;
/** Assembly adapter for module-local admission ports. Null provider represents unconfigured scopes. */
public final class OperationalInputGate {
    private final OperationalAdmissionService admission;
    public OperationalInputGate(OperationalAdmissionService admission){this.admission=admission;}
    public boolean newRunAllowed(String sourceId){return allowed(Operation.NEW_RUN,sourceId);}
    public boolean preparationAllowed(){return allowed(Operation.PREPARE_ANALYSIS,null);}
    public boolean dispatchAllowed(){return allowed(Operation.DISPATCH_AI,null);}
    public boolean uploadAllowed(){return allowed(Operation.UPLOAD_OBSERVATION,null);}
    private boolean allowed(Operation operation,String source){return admission!=null&&admission.check(operation,source).allowed();}
}
