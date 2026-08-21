package io.pipemesh.grpc;

import io.grpc.Metadata;
import io.pipemesh.core.capability.Principal;

/**
 * Works out who a remote caller is.
 *
 * <p>The runtime cannot authenticate anyone — it has no idea what a valid token
 * looks like in your deployment — so this is where an application plugs in the
 * answer: validate a bearer token, read a client certificate, check a header
 * against a directory.
 *
 * <p><b>Never derive this from the request body.</b> The proto has no field for a
 * caller's permissions and never will: a request that carries its own answer to
 * "what am I allowed to do" has not been authorised, it has been asked politely.
 *
 * <p>The default answers {@link Principal#ANONYMOUS}, which holds nothing. A
 * capability that asks for no permissions still works; one that asks for any is
 * refused until an application supplies a real resolver. Failing closed is the
 * only safe direction for a default nobody chose.
 */
@FunctionalInterface
public interface PrincipalResolver {

    PrincipalResolver ANONYMOUS = metadata -> Principal.ANONYMOUS;

    Principal resolve(Metadata metadata);
}
