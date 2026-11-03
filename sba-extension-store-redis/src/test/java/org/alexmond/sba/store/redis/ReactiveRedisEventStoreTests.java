package org.alexmond.sba.store.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceInfoChangedEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.Info;
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

	// --- retention (#24): Redis mirrors SBA's compacted in-memory log, never "the newest N" ---

	private ReactiveRedisEventStore store(int maxLogSize, boolean pruneInfoUpdated) {
		return new ReactiveRedisEventStore(this.redis.template, PREFIX, Duration.ofSeconds(3), Duration.ofHours(24),
				false, maxLogSize, pruneInfoUpdated);
	}

	private static InstanceEvent info(String id, long version) {
		return new InstanceInfoChangedEvent(InstanceId.of(id), version, Info.from(Map.of("seq", version)));
	}

	/** Registers {@code id} at version 0, then flips its status {@code changes} times. */
	private static void churn(ReactiveRedisEventStore store, String id, String source, int changes) {
		store.append(List.of(registered(id, source))).block();
		LongStream.rangeClosed(1, changes)
			.forEach((v) -> store.append(List.of(status(id, v, (v % 2 == 0) ? "UP" : "DOWN"))).block());
	}

	private List<Long> redisVersions(String id) {
		return this.redis.zsets.get(PREFIX + ":events:" + id).keySet().stream().map(Double::longValue).toList();
	}

	private static List<Long> memoryVersions(ReactiveRedisEventStore store, String id) {
		return store.find(InstanceId.of(id)).map(InstanceEvent::getVersion).collectList().block();
	}

	@Test
	void compactionKeepsRedisBoundedAndInStepWithMemory() {
		ReactiveRedisEventStore store = store(10, false);
		churn(store, "c1", "http-api", 500);

		List<Long> kept = memoryVersions(store, "c1");
		assertThat(kept).hasSizeLessThanOrEqualTo(10);
		assertThat(redisVersions("c1")).isEqualTo(kept);
		// The registration is the oldest event; trimming to "the newest N" would have dropped it.
		assertThat(kept).contains(0L);
	}

	@Test
	void plainAppendsDoNotRewriteTheKey() {
		ReactiveRedisEventStore store = store(100, false);
		churn(store, "p1", "http-api", 20);
		assertThat(this.redis.replaces).hasValue(1); // only the first write of the process
		assertThat(redisVersions("p1")).hasSize(21);
	}

	@Test
	void aCompactedLogStillHydratesAsARegisteredInstance() {
		churn(store(10, false), "h1", "http-api", 500);
		churn(store(10, false), "d1", "discovery", 500);

		ReactiveRedisEventStore restarted = store(10, false);
		// d1 is still recognised as discovery-sourced, because its registration survived compaction.
		assertThat(restarted.hydrate().block()).isEqualTo(1);
		List<InstanceEvent> events = restarted.find(InstanceId.of("h1")).collectList().block();
		assertThat(events.get(0)).isInstanceOf(InstanceRegisteredEvent.class);
		assertThat(events.get(events.size() - 1).getVersion()).isEqualTo(500L);
		assertThat(restarted.find(InstanceId.of("d1")).collectList().block()).isEmpty();
	}

	@Test
	void hydrationShrinksAnOversizedKeyWrittenBeforeTrimming() {
		churn(store(10_000, false), "big", "http-api", 300); // as written by the old, untrimmed mirror
		assertThat(redisVersions("big")).hasSize(301);

		ReactiveRedisEventStore restarted = store(100, false);
		assertThat(restarted.hydrate().block()).isEqualTo(1);
		assertThat(redisVersions("big")).isEqualTo(memoryVersions(restarted, "big")).hasSizeLessThan(100);
	}

	@Test
	void theFirstWriteOfAProcessReplacesALeftoverLog() {
		// A log from an earlier registration of the same id (e.g. hydration skipped it as discovery-
		// sourced, and discovery re-registered the instance from version 0).
		churn(store(), "r1", "discovery", 50);

		ReactiveRedisEventStore restarted = store();
		restarted.append(List.of(registered("r1", "discovery"))).block();
		assertThat(redisVersions("r1")).containsExactly(0L);
	}

	@Test
	void theWriteAfterAFailedOneRepairsTheGap() {
		ReactiveRedisEventStore store = store();
		store.append(List.of(registered("g1", "http-api"))).block();
		this.redis.down = true;
		store.append(List.of(status("g1", 1, "UP"))).block(); // lost
		this.redis.down = false;
		store.append(List.of(status("g1", 2, "DOWN"))).block();

		assertThat(redisVersions("g1")).containsExactly(0L, 1L, 2L);
	}

	@Test
	void honoursSbasPruneInfoUpdatedEvents() {
		ReactiveRedisEventStore store = store(100, true);
		store.append(List.of(registered("i1", "http-api"))).block();
		LongStream.rangeClosed(1, 5).forEach((v) -> store.append(List.of(info("i1", v))).block());

		assertThat(memoryVersions(store, "i1")).containsExactly(0L, 5L);
		assertThat(redisVersions("i1")).containsExactly(0L, 5L);
	}

	@Test
	void rejectsANonPositiveLogSize() {
		assertThatThrownBy(() -> store(0, false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxLogSizePerInstance");
	}

}
