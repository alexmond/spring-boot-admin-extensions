package org.alexmond.sba.store.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Store-specific config for the Redis SBA event store. The Redis <em>connection</em> uses the
 * standard {@code spring.data.redis.*} properties (host/port/password/ssl); this only covers what's
 * specific to the store. Activated by {@code sba.eventstore.type=redis}.
 *
 * @param keyPrefix key prefix for the store's Redis keys ({@code <prefix>:events:<id>},
 *     {@code <prefix>:instances})
 * @param timeout timeout for each individual Redis command (write-behind mirror, hydration read, ping)
 * @param eventTtl TTL applied to each per-instance event key, refreshed on every write. An instance
 *     that stops being written (e.g. one that re-registered under a new id — a k8s pod restarting
 *     under a new IP, an autoscaled/replaced node) self-evicts after this, so the mirror doesn't grow
 *     ghosts. Zero/negative disables expiry (keys live forever — not recommended for churny fleets).
 *     This is <em>not</em> a retention limit: a live instance's key is refreshed on every write and
 *     never expires; {@code maxLogSizePerInstance} bounds how much is kept for it.
 * @param hydrateOnStartup replay the persisted log into memory on startup; disable to always start
 *     from an empty registry
 * @param hydrateDiscovered whether startup hydration restores <em>discovery-sourced</em> instances
 *     (registration {@code source="discovery"}, set by SBA's {@code InstanceDiscoveryListener} for any
 *     Spring Cloud {@code DiscoveryClient} — Kubernetes, Eureka, Consul, …). Default {@code false}:
 *     skip them, because discovery re-registers the currently-live ones and prunes the rest, so
 *     replaying them would only resurrect dead instances as ghosts until the next discovery scan.
 *     Instances discovery will NOT restore (client self-registrations) are always hydrated regardless.
 * @param maxLogSizePerInstance compaction threshold for each instance's event log, applied to memory
 *     and Redis alike (SBA's own default is 100). Once an instance's log grows past this, SBA keeps
 *     only the newest event of each type (and of each status), and the Redis key is rewritten to
 *     match. Must be positive. To also keep only the newest INFO_UPDATED event on every write, set
 *     SBA's {@code spring.boot.admin.event-store.prune-info-updated-events=true}; this store honours it.
 */
@ConfigurationProperties(prefix = "sba.eventstore.redis")
public record RedisEventStoreProperties(@DefaultValue("sba:eventstore") String keyPrefix,
		@DefaultValue("3s") Duration timeout, @DefaultValue("24h") Duration eventTtl,
		@DefaultValue("true") boolean hydrateOnStartup, @DefaultValue("false") boolean hydrateDiscovered,
		@DefaultValue("100") int maxLogSizePerInstance) {
}
