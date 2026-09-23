package com.github.lisama569.apigatewayjava.upstream;

import com.github.lisama569.apigatewayjava.model.UpstreamRequest;
import com.github.lisama569.apigatewayjava.model.UpstreamResult;

/**
 * A local, in-JVM upstream used instead of any external service.
 */
public interface UpstreamClient {

    String key();

    UpstreamResult invoke(UpstreamRequest request);
}
