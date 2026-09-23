package com.github.lisama569.apigatewayjava.version;

import com.github.lisama569.apigatewayjava.model.VersionDescriptor;
import com.github.lisama569.apigatewayjava.model.VersionOutcome;
import com.github.lisama569.apigatewayjava.model.VersionResolution;
import com.github.lisama569.apigatewayjava.model.VersionStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds registered resource versions and resolves version requests.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>resource or version never registered -> {@link VersionOutcome#UNKNOWN_VERSION}</li>
 *   <li>version registered but marked REMOVED -> {@link VersionOutcome#VERSION_REMOVED}</li>
 *   <li>ACTIVE / DEPRECATED -> {@link VersionOutcome#OK}; deprecation is carried as metadata,
 *       the call itself is still served</li>
 * </ul>
 */
@Component
public class VersionRegistry {

    private static final String ORDERS_UPSTREAM = "orders-upstream";

    private final Map<String, List<VersionDescriptor>> byResource;

    public VersionRegistry() {
        this.byResource = Map.of(
                "orders", List.of(
                        new VersionDescriptor("v1", "orders", VersionStatus.REMOVED,
                                ORDERS_UPSTREAM, true,
                                "orders v1 was removed on 2026-06-30; please migrate to v3"),
                        new VersionDescriptor("v2", "orders", VersionStatus.DEPRECATED,
                                ORDERS_UPSTREAM, true,
                                "orders v2 is deprecated and will be removed on 2026-12-31; please migrate to v3"),
                        new VersionDescriptor("v3", "orders", VersionStatus.ACTIVE,
                                ORDERS_UPSTREAM, true, null)
                ),
                "payments", List.of(
                        // non-idempotent: must never be retried after dispatch
                        new VersionDescriptor("v1", "payments", VersionStatus.ACTIVE,
                                ORDERS_UPSTREAM, false, null)
                )
        );
    }

    public VersionResolution resolve(String resource, String version) {
        List<VersionDescriptor> descriptors = byResource.get(resource);
        if (descriptors == null) {
            return new VersionResolution(VersionOutcome.UNKNOWN_VERSION, null,
                    "unknown resource '" + resource + "'; known resources: " + byResource.keySet());
        }
        Optional<VersionDescriptor> match = descriptors.stream()
                .filter(d -> d.version().equals(version))
                .findFirst();
        if (match.isEmpty()) {
            Set<String> registered = descriptors.stream()
                    .map(VersionDescriptor::version)
                    .collect(Collectors.toSet());
            return new VersionResolution(VersionOutcome.UNKNOWN_VERSION, null,
                    "unknown version '" + version + "' for resource '" + resource
                            + "'; registered versions: " + registered);
        }
        VersionDescriptor descriptor = match.get();
        return switch (descriptor.status()) {
            case REMOVED -> new VersionResolution(VersionOutcome.VERSION_REMOVED, descriptor,
                    descriptor.sunsetNote());
            case DEPRECATED -> new VersionResolution(VersionOutcome.OK, descriptor,
                    "DEPRECATED: " + descriptor.sunsetNote());
            case ACTIVE -> new VersionResolution(VersionOutcome.OK, descriptor, null);
        };
    }
}
