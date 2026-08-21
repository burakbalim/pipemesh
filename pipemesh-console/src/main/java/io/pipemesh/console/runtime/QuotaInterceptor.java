package io.pipemesh.console.runtime;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.pipemesh.console.subscription.QuotaExceededException;
import io.pipemesh.console.subscription.SubscriptionService;
import io.pipemesh.core.capability.Principal;
import org.springframework.stereotype.Component;

/**
 * Refuses work an organization's plan no longer covers (§39.1).
 *
 * <p>In front of the runtime rather than inside it. The engine knows what a step
 * costs and stops a single execution that overruns its own budget; it knows
 * nothing about subscriptions, periods or plans, and putting any of that inside
 * it would be the application's business leaking into the runtime (§3).
 *
 * <p>Only starting is gated. Reading an execution, resuming one, or watching one
 * already under way costs a plan nothing and refusing them would strand work
 * somebody already paid for.
 */
@Component
public class QuotaInterceptor implements ServerInterceptor {

    private static final String START = "pipemesh.v1.PipeMesh/StartExecution";
    private static final String PROCESS = "pipemesh.v1.PipeMesh/ProcessMessage";

    private final ConsolePrincipalResolver principals;
    private final SubscriptionService subscriptions;

    public QuotaInterceptor(
            ConsolePrincipalResolver principals, SubscriptionService subscriptions) {

        this.principals = principals;
        this.subscriptions = subscriptions;
    }

    @Override
    public <Q, A> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, A> call, Metadata metadata, ServerCallHandler<Q, A> next) {

        if (!startsWork(call.getMethodDescriptor().getFullMethodName())) {
            return next.startCall(call, metadata);
        }

        Principal caller = principals.resolve(metadata);
        // A caller nobody identified has no plan to be over. Whether they may be
        // here at all is the permission model's question, not this one's (§23).
        if (caller.organizationIfKnown().isEmpty()) {
            return next.startCall(call, metadata);
        }

        try {
            subscriptions.refuseIfExhausted(caller.organizationIfKnown().orElseThrow().value());
        } catch (QuotaExceededException exhausted) {
            // RESOURCE_EXHAUSTED, not PERMISSION_DENIED: nothing is wrong with
            // this caller, and the same call works again next period.
            call.close(Status.RESOURCE_EXHAUSTED.withDescription(exhausted.getMessage()), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        return next.startCall(call, metadata);
    }

    private static boolean startsWork(String method) {
        return START.equals(method) || PROCESS.equals(method);
    }
}
