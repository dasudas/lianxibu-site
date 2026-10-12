package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.DeepSeekChatClient;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Consumes a finished HTTP attempt. Storage failures propagate and never cause another send. */
public final class AttemptOutcomeService {
    public enum State { APPLIED, RETAINED, KNOWN_FAILURE, UNKNOWN }
    public record Result(State state) { }
    public record Application(ResultApplicationService.Input input,ResultApplicationService service) {
        public Application {Objects.requireNonNull(input);Objects.requireNonNull(service);}
    }
    @FunctionalInterface public interface Applications {Application forAttempt(WorkerAuthorizationService.Identity identity);}
    private final AttemptUsageService usage;
    private final AttemptFailureService failures;
    private final TransportUnknownService unknown;
    private final Applications applications;
    public AttemptOutcomeService(AttemptUsageService usage,AttemptFailureService failures,TransportUnknownService unknown,Applications applications) {
        this.usage=Objects.requireNonNull(usage);this.failures=Objects.requireNonNull(failures);this.unknown=Objects.requireNonNull(unknown);this.applications=Objects.requireNonNull(applications);
    }
    public Result consume(WorkerAuthorizationService.Identity id,DeepSeekChatClient.Outcome outcome) {
        Objects.requireNonNull(id);Objects.requireNonNull(outcome);
        if(outcome.status()==DeepSeekChatClient.Status.UNKNOWN) {
            unknown.release(id,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action(id,"unknown"));return new Result(State.UNKNOWN);
        }
        var reported=outcome.usage();
        usage.record(id,new AttemptUsageService.Metadata(outcome.providerRequestId(),reported==null?null:reported.promptTokens(),reported==null?null:reported.completionTokens(),reported==null?null:reported.totalTokens(),reported==null?null:reported.cacheHitTokens()),action(id,"usage"));
        if(outcome.status()==DeepSeekChatClient.Status.VALID) {
            var application=Objects.requireNonNull(applications.forAttempt(id));var input=application.input();
            if(!id.rootId().equals(input.rootId())||!id.attemptId().equals(input.attemptId())||!id.inputHash().equals(input.inputHash()))throw new IllegalStateException("original application input required");
            String envelope;
            try {envelope=ResultApplicationService.providerEnvelope(input,outcome.contentJson());}
            catch(ResultApplicationService.Rejected invalidPayload) {return fail(id,AttemptFailureService.Failure.INVALID_RESULT,null);}
            // Application and database errors must propagate outside the pure-format catch.
            var applied=application.service().applyAutomatic(input,envelope,action(id,"apply"));
            return new Result(applied.status()==ResultApplicationService.Status.APPLIED||applied.status()==ResultApplicationService.Status.REPLAYED?State.APPLIED:State.RETAINED);
        }
        var failure=switch(outcome.status()) {
            case INVALID_JSON -> AttemptFailureService.Failure.INVALID_RESULT;
            case BLOCKED_CONFIGURATION -> AttemptFailureService.Failure.CONFIGURATION;
            case DEFINITE_FAILURE -> outcome.httpStatus()!=null&&(outcome.httpStatus()==429||outcome.httpStatus()>=500&&outcome.httpStatus()<=599)
                    ?AttemptFailureService.Failure.TRANSIENT:AttemptFailureService.Failure.CONFIGURATION;
            default -> throw new IllegalStateException("provider outcome unsupported");
        };
        return fail(id,failure,outcome.retryAfterSeconds());
    }
    private Result fail(WorkerAuthorizationService.Identity id,AttemptFailureService.Failure failure,Integer delay) {
        failures.fail(id,failure,delay,action(id,"failure"));return new Result(State.KNOWN_FAILURE);
    }
    private static SharedWriteExecutor.ActionContext action(WorkerAuthorizationService.Identity id,String step) {
        var value=JsonMapper.shared().createObjectNode();value.put("root_id",id.rootId());value.put("attempt_id",id.attemptId());value.put("dispatch_token",id.dispatchToken());value.put("slot_revision",id.slotRevision());value.put("fencing",id.fencing());
        value.put("input_hash",id.inputHash());value.put("step",step);
        return new SharedWriteExecutor.ActionContext("provider-outcome:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,value),id.attemptId(),id.inputHash());
    }
}
