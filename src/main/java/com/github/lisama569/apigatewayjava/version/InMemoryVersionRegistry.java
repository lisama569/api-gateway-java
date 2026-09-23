package com.github.lisama569.apigatewayjava.version;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版本注册表（本地实现，不依赖外部服务）。
 *
 * 版本生命周期：ACTIVE -> DEPRECATED -> REMOVED。
 * <ul>
 *   <li>ACTIVE / DEPRECATED：均可正常访问；DEPRECATED 版本在响应中携带可识别的废弃提示。</li>
 *   <li>REMOVED：resolve 仍能查到（携带移除原因），由上层明确拒绝（410），
 *       与「从未登记过的版本」（查不到，404）严格区分。</li>
 * </ul>
 */
@Component
public class InMemoryVersionRegistry implements VersionRegistry {

    private final Map<String, Map<String, VersionedResource>> table = new ConcurrentHashMap<>();

    public InMemoryVersionRegistry() {
        register("orders", "v1", VersionStatus.ACTIVE, null, null);
        register("orders", "v2", VersionStatus.ACTIVE, null, null);
        register("orders", "v0", VersionStatus.DEPRECATED,
                "orders v0 已废弃，计划于 2027-01-01 移除，请迁移至 v2", null);
        register("orders", "v-beta", VersionStatus.REMOVED, null,
                "orders v-beta 已于 2026-06-01 正式移除，请使用 v2");
        register("users", "v1", VersionStatus.ACTIVE, null, null);
        register("users", "v0", VersionStatus.DEPRECATED,
                "users v0 已废弃，计划于 2027-01-01 移除，请迁移至 v1", null);
        register("payments", "v1", VersionStatus.ACTIVE, null, null);
    }

    private void register(String resource, String version, VersionStatus status,
                          String deprecationNotice, String removalReason) {
        table.computeIfAbsent(resource, k -> new ConcurrentHashMap<>())
                .put(version, new VersionedResource(resource, version, status,
                        deprecationNotice, removalReason));
    }

    /**
     * @return 已登记版本（含 REMOVED，携带移除原因）；从未登记返回 empty，由上层映射为 VERSION_UNKNOWN
     */
    @Override
    public Optional<VersionedResource> resolve(String resource, String version) {
        Map<String, VersionedResource> versions = table.get(resource);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(version));
    }
}
