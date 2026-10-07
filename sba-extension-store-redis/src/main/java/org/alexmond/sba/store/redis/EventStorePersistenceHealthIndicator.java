package org.alexmond.sba.store.redis;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import reactor.core.publisher.Mono;

/**
 * Makes the Redis mirror's state <em>visible</em> without ever taking the admin down.
 * Reports {@link Status#UP} when Redis is reachable, and a custom
 * <strong>{@code DEGRADED}</strong> status when it isn't — meaning the registry is still
 * fully served from memory but persistence is paused.
 *
 * <p>
 * {@code DEGRADED} is deliberately <em>not</em> {@code DOWN}: mapped to HTTP 200 and kept
 * out of the readiness/liveness groups (see the admin's {@code application.yml}), so a
 * Redis outage shows up loudly in {@code /actuator/health} and the SBA console but never
 * trips the k8s probes into restarting the pod. This is the fix for the old blind spot
 * where {@code health.redis.enabled=false} hid the outage entirely and the admin reported
 * UP while its registry had silently emptied.
 */
public class EventStorePersistenceHealthIndicator implements ReactiveHealthIndicator {

	/**
	 * Reachable-but-impaired: still serving from memory, Redis persistence paused. HTTP
	 * 200.
	 */
	static final Status DEGRADED = new Status("DEGRADED",
			"SBA registry served from memory; Redis persistence is paused");

	private final ReactiveRedisEventStore store;

	public EventStorePersistenceHealthIndicator(ReactiveRedisEventStore store) {
		this.store = store;
	}

	@Override
	public Mono<Health> health() {
		return this.store.ping().map((reachable) -> {
			ReactiveRedisEventStore.PersistenceStatus s = this.store.persistenceStatus();
			Health.Builder b = (reachable ? Health.up() : Health.status(DEGRADED)).withDetail("backend", "redis")
				.withDetail("reachable", reachable)
				.withDetail("mode", reachable ? "persistent" : "in-memory-fallback")
				.withDetail("mirrorSuccesses", s.mirrorSuccesses())
				.withDetail("mirrorFailures", s.mirrorFailures());
			if (s.lastSuccessAt() != null) {
				b.withDetail("lastSuccessAt", s.lastSuccessAt().toString());
			}
			if (!reachable) {
				if (s.lastFailureAt() != null) {
					b.withDetail("lastFailureAt", s.lastFailureAt().toString());
				}
				if (s.lastError() != null) {
					b.withDetail("lastError", s.lastError());
				}
			}
			return b.build();
		});
	}

}
