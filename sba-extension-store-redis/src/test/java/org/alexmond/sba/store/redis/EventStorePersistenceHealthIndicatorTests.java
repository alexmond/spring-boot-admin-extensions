package org.alexmond.sba.store.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;

import static org.assertj.core.api.Assertions.assertThat;

class EventStorePersistenceHealthIndicatorTests {

	private final FakeRedis redis = new FakeRedis();

	private final ReactiveRedisEventStore store = new ReactiveRedisEventStore(this.redis.template, "sba:test");

	private final EventStorePersistenceHealthIndicator indicator = new EventStorePersistenceHealthIndicator(
			this.store);

	@Test
	void reportsUpWhileRedisIsReachable() {
		this.store.append(List.of(new InstanceStatusChangedEvent(InstanceId.of("a"), 0, StatusInfo.ofUp()))).block();

		Health health = this.indicator.health().block();
		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("backend", "redis")
			.containsEntry("reachable", true)
			.containsEntry("mode", "persistent")
			.containsEntry("mirrorSuccesses", 1L)
			.containsEntry("mirrorFailures", 0L)
			.containsKey("lastSuccessAt")
			.doesNotContainKeys("lastFailureAt", "lastError");
	}

	@Test
	void reportsDegradedNotDownWhenRedisIsUnreachable() {
		this.redis.down = true;

		Health health = this.indicator.health().block();
		// DEGRADED, never DOWN: the registry is still served from memory, so the pod must not be
		// restarted or taken out of rotation because Redis is away.
		assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
		assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
		assertThat(health.getDetails()).containsEntry("reachable", false)
			.containsEntry("mode", "in-memory-fallback")
			.containsKeys("lastFailureAt", "lastError");
	}

}
