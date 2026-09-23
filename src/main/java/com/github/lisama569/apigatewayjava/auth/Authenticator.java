package com.github.lisama569.apigatewayjava.auth;

import com.github.lisama569.apigatewayjava.model.AuthResult;
import com.github.lisama569.apigatewayjava.model.AuthOutcome;
import com.github.lisama569.apigatewayjava.model.Caller;
import org.springframework.stereotype.Component;

/**
 * Authenticates callers and authorizes resource access.
 *
 * <p>The two failure modes are kept distinguishable:
 * <ul>
 *   <li>{@link AuthOutcome#INVALID_IDENTITY} - no/unknown API key</li>
 *   <li>{@link AuthOutcome#FORBIDDEN} - known caller without permission for the resource</li>
 * </ul>
 */
@Component
public class Authenticator {

    private final CallerDirectory directory;

    public Authenticator(CallerDirectory directory) {
        this.directory = directory;
    }

    public AuthResult authenticate(String apiKey, String resource) {
        Caller caller = directory.find(apiKey);
        if (caller == null) {
            return new AuthResult(AuthOutcome.INVALID_IDENTITY, null,
                    "missing or unknown API key");
        }
        if (!caller.resources().contains(resource)) {
            return new AuthResult(AuthOutcome.FORBIDDEN, caller,
                    "caller '" + caller.name() + "' is not authorized for resource '"
                            + resource + "'; allowed: " + caller.resources());
        }
        return new AuthResult(AuthOutcome.ALLOWED, caller,
                "caller '" + caller.name() + "' authorized for '" + resource + "'");
    }
}
