package com.github.lisama569.apigatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * Unified gateway envelope.
 *
 * @param success     overall success flag
 * @param code        stable result code ("OK" or a GatewayErrorCode name)
 * @param httpStatus  suggested HTTP status
 * @param resource    requested resource
 * @param version     requested version
 * @param body        upstream body on success
 * @param notices     advisory notices, e.g. deprecation
 * @param explain     machine/human-readable decision details
 * @param attempts    physical upstream attempts
 * @param degraded    whether a fallback was served
 */
public record GatewayResponse(
        boolean success,
        String code,
        int httpStatus,
        String resource,
        String version,
        String body,
        List<String> notices,
        Map<String, Object> explain,
        int attempts,
        boolean degraded
) {
}
