package com.github.lisama569.apigatewayjava.identity;

import java.util.Optional;

public interface CallerDirectory {
    Optional<CallerProfile> find(String callerId);
}
