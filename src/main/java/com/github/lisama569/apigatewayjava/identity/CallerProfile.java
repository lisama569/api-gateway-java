package com.github.lisama569.apigatewayjava.identity;

import java.util.Set;

public record CallerProfile(String callerId, Set<String> allowedResources, long quotaLimit) {
}
