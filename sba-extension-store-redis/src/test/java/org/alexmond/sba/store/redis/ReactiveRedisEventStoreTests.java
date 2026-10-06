package org.alexmond.sba.store.redis;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.eventstore.OptimisticLockingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ReactiveRedisEventStore} against {@link FakeRedis}: they need no Redis and
 * pin the module's central promise — a Redis outage never blanks the registry and never fails a
 * write. {@code ReactiveRedisEventStoreIT} repeats the happy path against a real Redis.
 */
class ReactiveRedisEventStoreTests {

	private static final String PREFIX = "sba:test";

	private final FakeRedis redis = new FakeRedis();

	private ReactiveRedisEventStore store() {
		return new ReactiveRedisEventStore(this.redis.template, PREFIX);
	}

	private static InstanceEvent status(String id, long version, String state) {
		return new InstanceStatusChangedEvent(InstanceId.of(id), version, StatusInfo.valueOf(state));
	}

	private static InstanceEvent registered(String id, String source) {
		return new InstanceRegisteredEvent(InstanceId.of(id), 0,
				Registration.create("app-" + id, "http://" + id + "/health").source(source).build());
	}

	@Test
	void mirrorsEveryAppendAndTracksTheInstance() {
		ReactiveRedisEventStore store = store();
		store.append(List.of(status("i1", 0, "UP"))).block();
		store.append(List.of(status("i1", 1, "DOWN"))).block();

		assertThat(this.redis.zsets.get(PREFIX + ":events:i1")).hasSize(2);
		assertThat(this.redis.sets.get(PREFIX + ":instances")).containsExactly("i1");
		assertThat(store.persistenceStatus().mirrorSuccesses()).isEqualTo(2);
		assertThat(store.persistenceStatus().reachable()).isTrue();
		assertThat(store.persistenceStatus().lastSuccessAt()).isNotNull();
		assertThat(store.persistenceStatus().lastError()).isNull();
	}

	@Test
	void aFreshStoreHydratesFromTheMirrorInVersionOrder() {
		ReactiveRedisEventStore writer = store();
		writer.append(List.of(status("h1", 0, "UP"))).block();
		writer.append(List.of(status("h1", 1, "DOWN"))).block();
		writer.append(List.of(status("h2", 0, "UP"))).block();

		ReactiveRedisEventStore restarted = store();
		assertThat(restarted.hydrate().block()).isEqualTo(2);
		assertThat(restarted.find(InstanceId.of("h1")).collectList().block()).extracting(InstanceEvent::getVersion)
			.containsExactly(0L, 1L);
		assertThat(restarted.findAll().collectList().block()).extracting((e) -> e.getInstance().getValue())
			.contains("h1", "h2");
		// Hydration replays into memory only; it must not write the events back to Redis.
		assertThat(restarted.persistenceStatus().mirrorSuccesses()).isZero();
	}

	@Test
	void hydrateSkipsDiscoverySourcedInstancesUnlessAskedTo() {
		ReactiveRedisEventStore writer = store();
		writer.append(List.of(registered("disc1", "discovery"))).block();
		writer.append(List.of(registered("client1", "http-api"))).block();

		ReactiveRedisEventStore restarted = store();
		assertThat(restarted.hydrate().block()).isEqualTo(1);
		assertThat(restarted.find(InstanceId.of("disc1")).collectList().block()).isEmpty();
		assertThat(restarted.find(InstanceId.of("client1")).collectList().block()).hasSize(1);

		ReactiveRedisEventStore restartedAll = new ReactiveRedisEventStore(this.redis.template, PREFIX,
				Duration.ofSeconds(3), Duration.ofHours(24), true);
		assertThat(restartedAll.hydrate().block()).isEqualTo(2);
	}

	@Test
	void hydrateLetsLiveEventsWinOverThePersistedLog() {
		store().append(List.of(status("live", 0, "UP"))).block();

		ReactiveRedisEventStore restarted = store();
		// Discovery registered the instance before hydration ran.
		restarted.append(List.of(status("live", 0, "DOWN"))).block();
		assertThat(restarted.hydrate().block()).isZero();
		assertThat(restarted.find(InstanceId.of("live")).collectList().block()).hasSize(1);
	}

	@Test
	void hydratePrunesInstanceIdsWhoseEventKeyHasExpired() {
		store().append(List.of(status("gone", 0, "UP"))).block();
		this.redis.zsets.remove(PREFIX + ":events:gone"); // the TTL fired

		assertThat(store().hydrate().block()).isZero();
		assertThat(this.redis.sets.get(PREFIX + ":instances")).isEmpty();
	}

	@Test
	void refreshesTheTtlOnEveryWrite() {
		ReactiveRedisEventStore store = new ReactiveRedisEventStore(this.redis.template, PREFIX,
				Duration.ofSeconds(3), Duration.ofMinutes(30), false);
		store.append(List.of(status("t1", 0, "UP"))).block();
		assertThat(this.redis.ttls).containsEntry(PREFIX + ":events:t1", Duration.ofMinutes(30));
	}

	@Test
	void aZeroOrNegativeTtlMeansNeverExpire() {
		for (Duration ttl : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
			ReactiveRedisEventStore store = new ReactiveRedisEventStore(this.redis.template, PREFIX,
					Duration.ofSeconds(3), ttl, false);
			store.append(List.of(status("n" + ttl.getSeconds(), 0, "UP"))).block();
		}
		assertThat(this.redis.ttls).isEmpty();
		assertThat(this.redis.zsets).hasSize(2);
	}

	@Test
	void aRedisOutageNeverFailsAWriteAndNeverBlanksTheRegistry() {
		ReactiveRedisEventStore store = store();
		store.append(List.of(status("o1", 0, "UP"))).block();
		this.redis.down = true;

		store.append(List.of(status("o1", 1, "DOWN"))).block(); // must not throw
		store.append(List.of(status("o2", 0, "UP"))).block();

		assertThat(store.find(InstanceId.of("o1")).collectList().block()).extracting(InstanceEvent::getVersion)
			.containsExactly(0L, 1L);
		assertThat(store.findAll().collectList().block()).hasSize(3);
		ReactiveRedisEventStore.PersistenceStatus status = store.persistenceStatus();
		assertThat(status.reachable()).isFalse();
		assertThat(status.mirrorSuccesses()).isEqualTo(1);
		assertThat(status.mirrorFailures()).isEqualTo(2);
		assertThat(status.lastFailureAt()).isNotNull();
		assertThat(status.lastError()).contains("simulated outage");
	}

	@Test
	void theOptimisticLockStillHoldsDuringAnOutage() {
		ReactiveRedisEventStore store = store();
		this.redis.down = true;
		store.append(List.of(status("l1", 0, "UP"))).block();
		assertThatThrownBy(() -> store.append(List.of(status("l1", 0, "DOWN"))).block())
			.isInstanceOf(OptimisticLockingException.class);
	}

	@Test
	void hydrateDuringAnOutageStartsEmptyInsteadOfFailing() {
		store().append(List.of(status("x1", 0, "UP"))).block();
		this.redis.down = true;

		ReactiveRedisEventStore restarted = store();
		assertThat(restarted.hydrate().block()).isZero();
		assertThat(restarted.findAll().collectList().block()).isEmpty();
		assertThat(restarted.persistenceStatus().reachable()).isFalse();
	}

	@Test
	void pingFollowsRedisDownAndBackUp() {
		ReactiveRedisEventStore store = store();
		assertThat(store.ping().block()).isTrue();

		this.redis.down = true;
		assertThat(store.ping().block()).isFalse();
		assertThat(store.persistenceStatus().reachable()).isFalse();

		this.redis.down = false;
		assertThat(store.ping().block()).isTrue();
		assertThat(store.persistenceStatus().reachable()).isTrue();
		assertThat(store.persistenceStatus().lastError()).isNull();
	}

	@Test
	void aSlowRedisIsCutOffByTheTimeoutAndCountedAsAFailure() {
		ReactiveRedisEventStore store = new ReactiveRedisEventStore(new HangingRedis().template, PREFIX,
				Duration.ofMillis(50), Duration.ofHours(24), false);
		assertThat(store.ping().block(Duration.ofSeconds(5))).isFalse();
		assertThat(store.persistenceStatus().lastError()).containsIgnoringCase("timeout");
	}

}
