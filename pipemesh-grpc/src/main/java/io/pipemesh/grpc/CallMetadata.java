package io.pipemesh.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Carries a call's metadata to the code that needs to know who is calling.
 *
 * <p>gRPC hands metadata to an interceptor and the request to the service, and
 * they meet nowhere by default. This puts the metadata in the call's context so
 * the service can ask the resolver about the caller — without the caller's own
 * claims ever entering the picture (§23).
 */
public final class CallMetadata implements ServerInterceptor {

    static final Context.Key<Metadata> HEADERS = Context.key("pipemesh.headers");

    @Override
    public <Q, S> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {

        return Contexts.interceptCall(Context.current().withValue(HEADERS, headers), call, headers, next);
    }

    /** The current call's metadata, or an empty set when there is no call. */
    static Metadata current() {
        Metadata headers = HEADERS.get();
        return headers == null ? new Metadata() : headers;
    }
}
