package com.github.lisama569.apigatewayjava.upstream;

import java.util.List;

public interface UpstreamClient {
    UpstreamOutcome call(UpstreamRequest request);

    UpstreamOutcome callBatch(List<UpstreamRequest> requests);
}
