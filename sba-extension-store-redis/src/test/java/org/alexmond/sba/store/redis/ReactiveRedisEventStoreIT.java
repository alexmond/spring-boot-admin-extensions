package org.alexmond.sba.store.redis;

import java.time.Duration;
import java.util.List;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

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
 * Runs against a real Redis when SBA_REDIS_TEST_URI is set (e.g. a port-forwarded lab Redis:
 * {@code SBA_REDIS_TEST_URI=redis://:pw@localhost:16399}). Skipped otherwise.
 *
 * <p>Reads are in-memory-authoritative (the store extends {@code InMemoryEventStore}); Redis is a
 * write-behind mirror. So {@code find}/{@code findAll}/the optimistic lock are validated in memory,
 * and a separate test proves the mirror + hydrate round trip (durability across a "restart").
 */
@EnabledIfEnvironmentVariable(named = "SBA_REDIS_TEST_URI", matches = ".+")
class ReactiveRedisEventStoreIT {

	private static LettuceConnectionFactory cf;

	private static ReactiveStringRedisTemplate redis;

	private static ReactiveRedisEventStore store;

	private static final String PREFIX = "sba-it:" + System.nanoTime();

	@BeforeAll
	static void setup() {
		RedisURI uri = RedisURI.create(System.getenv("SBA_REDIS_TEST_URI"));
		RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
		// Lettuce 7 dropped RedisURI#getPassword(); credentials come from the URI's provider.
		RedisCredentials credentials = uri.getCredentialsProvider().resolveCredentials().block();
		if (credentials != null && credentials.hasPassword()) {
			cfg.setPassword(RedisPassword.of(credentials.getPassword()));
			if (credentials.hasUsername()) {
				cfg.setUsername(credentials.getUsername());
			}
		}
		cf = new LettuceConnectionFactory(cfg);
		cf.afterPropertiesSet();
		redis = new ReactiveStringRedisTemplate(cf);
		store = new ReactiveRedisEventStore(redis, PREFIX);
	}

	@AfterAll
	static void teardown() {
		if (redis != null) {
			redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").build()).flatMap(redis::delete).blockLast();
		}
		if (cf != null) {
			cf.destroy();
		}
	}

	private static InstanceEvent status(String id, long version, String state) {
		return new InstanceStatusChangedEvent(InstanceId.of(id), version, StatusInfo.valueOf(state));
	}

	/** Poll until the async write-behind mirror has confirmed at least {@code expected} writes. */
	private static void awaitMirror(ReactiveRedisEventStore s, long expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (s.persistenceStatus().mirrorSuccesses() < expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
	}

	@Test
	void appends_and_reads_back_in_version_order() {
		store.append(List.of(status("i1", 0, "UP"))).block();
		store.append(List.of(status("i1", 1, "DOWN"))).block();
		store.append(List.of(status("i1", 2, "UP"))).block();

		List<InstanceEvent> got = store.find(InstanceId.of("i1")).collectList().block();
		assertThat(got).extracting(InstanceEvent::getVersion).containsExactly(0L, 1L, 2L);
	}

	@Test
	void rejects_stale_or_equal_version_with_optimistic_lock() {
		store.append(List.of(status("i2", 0, "UP"))).block();
		store.append(List.of(status("i2", 1, "UP"))).block();

		assertThatThrownBy(() -> store.append(List.of(status("i2", 1, "DOWN"))).block())
			.isInstanceOf(OptimisticLockingException.class);
		assertThatThrownBy(() -> store.append(List.of(status("i2", 0, "DOWN"))).block())
			.isInstanceOf(OptimisticLockingException.class);

		// The rejected events must NOT have been written.
		assertThat(store.find(InstanceId.of("i2")).collectList().block()).extracting(InstanceEvent::getVersion)
			.containsExactly(0L, 1L);
	}

	@Test
	void publishes_appended_events_to_subscribers() {
		StepVerifier.create(Flux.from(store).take(1))
			.then(() -> store.append(List.of(status("i3", 0, "UP"))).subscribe())
			.assertNext((event) -> assertThat(event.getInstance()).isEqualTo(InstanceId.of("i3")))
			.verifyComplete();
	}

	@Test
	void findAll_returns_events_across_instances() {
		store.append(List.of(status("a", 0, "UP"))).block();
		store.append(List.of(status("b", 0, "UP"))).block();

		List<InstanceEvent> all = store.findAll().collectList().block();
		assertThat(all).extracting((event) -> event.getInstance().getValue()).contains("a", "b");
	}

	@Test
	void ping_reports_reachable_against_a_live_redis() {
		assertThat(store.ping().block()).isTrue();
		assertThat(store.persistenceStatus().reachable()).isTrue();
	}

	@Test
	void hydrate_skips_discovery_sourced_instances_by_default() throws InterruptedException {
		// Discovery restores its own instances on startup (and prunes dead ones), so hydrating them
		// would just resurrect ghosts (e.g. a k8s pod that restarted under a new id). Only non-discovery
		// (client self-registered) instances should be hydrated from Redis by default.
		ReactiveRedisEventStore writer = new ReactiveRedisEventStore(redis, PREFIX + ":src");
		InstanceId disc = InstanceId.of("disc1");
		writer.append(List.of(new InstanceRegisteredEvent(disc, 0,
				Registration.create("app-disc", "http://app-disc/health").source("discovery").build()))).block();
		InstanceId client = InstanceId.of("client1");
		writer.append(List.of(new InstanceRegisteredEvent(client, 0,
				Registration.create("app-client", "http://app-client/health").source("http-api").build()))).block();
		awaitMirror(writer, 2);

		ReactiveRedisEventStore restarted = new ReactiveRedisEventStore(redis, PREFIX + ":src");
		assertThat(restarted.hydrate().block()).isEqualTo(1); // only the client-sourced instance
		assertThat(restarted.find(disc).collectList().block()).isEmpty(); // discovery-sourced skipped
		assertThat(restarted.find(client).collectList().block()).hasSize(1); // client-sourced hydrated

		// With hydrateDiscovered=true, the discovery-sourced one is restored too.
		ReactiveRedisEventStore restartedAll = new ReactiveRedisEventStore(redis, PREFIX + ":src",
				Duration.ofSeconds(3), Duration.ofHours(24), true);
		assertThat(restartedAll.hydrate().block()).isEqualTo(2);
	}

	@Test
	void write_sets_a_ttl_on_the_event_key() throws InterruptedException {
		ReactiveRedisEventStore w = new ReactiveRedisEventStore(redis, PREFIX + ":ttl", Duration.ofSeconds(3),
				Duration.ofMinutes(30), false);
		w.append(List.of(status("t1", 0, "UP"))).block();
		awaitMirror(w, 1);
		Duration ttl = redis.getExpire(PREFIX + ":ttl:events:t1").block();
		assertThat(ttl).isBetween(Duration.ofMinutes(25), Duration.ofMinutes(30));
	}

	@Test
	void mirrors_to_redis_and_a_fresh_store_hydrates_from_it() throws InterruptedException {
		// A separate prefix/store pair so counters + keys are isolated from the other tests.
		ReactiveRedisEventStore writer = new ReactiveRedisEventStore(redis, PREFIX + ":hy");
		writer.append(List.of(status("h1", 0, "UP"))).block();
		writer.append(List.of(status("h1", 1, "DOWN"))).block();
		writer.append(List.of(status("h2", 0, "UP"))).block();

		// The write-behind mirror is async — wait for it to reach Redis (3 appends → 3 mirrors).
		awaitMirror(writer, 3);
		assertThat(writer.persistenceStatus().mirrorSuccesses()).isGreaterThanOrEqualTo(3);
		assertThat(writer.persistenceStatus().reachable()).isTrue();

		// A fresh store (simulating an admin restart) rebuilds its in-memory log from Redis.
		ReactiveRedisEventStore restarted = new ReactiveRedisEventStore(redis, PREFIX + ":hy");
		Integer hydrated = restarted.hydrate().block();
		assertThat(hydrated).isEqualTo(2);
		assertThat(restarted.find(InstanceId.of("h1")).collectList().block())
			.extracting(InstanceEvent::getVersion).containsExactly(0L, 1L);
		assertThat(restarted.findAll().collectList().block())
			.extracting((event) -> event.getInstance().getValue()).contains("h1", "h2");
	}

	@Test
	void compaction_keeps_the_real_key_bounded_and_hydratable() throws InterruptedException {
		// Runs the real Lua write script: Redis must end up holding exactly SBA's compacted log,
		// registration included, and a restart must rebuild a registered instance from it.
		String prefix = PREFIX + ":cmp";
		ReactiveRedisEventStore writer = new ReactiveRedisEventStore(redis, prefix, Duration.ofSeconds(3),
				Duration.ofMinutes(30), false, 10, false);
		InstanceId id = InstanceId.of("c1");
		writer.append(List.of(new InstanceRegisteredEvent(id, 0,
				Registration.create("app-c1", "http://app-c1/health").source("http-api").build()))).block();
		for (long v = 1; v <= 200; v++) {
			writer.append(List.of(status("c1", v, (v % 2 == 0) ? "UP" : "DOWN"))).block();
		}
		awaitMirror(writer, 201);

		List<Long> inMemory = writer.find(id).map(InstanceEvent::getVersion).collectList().block();
		List<Long> inRedis = redis.opsForZSet()
			.rangeWithScores(prefix + ":events:c1", Range.closed(0L, -1L))
			.map((t) -> t.getScore().longValue())
			.collectList()
			.block();
		assertThat(inMemory).hasSizeLessThanOrEqualTo(10).contains(0L, 200L);
		assertThat(inRedis).isEqualTo(inMemory);
		assertThat(redis.getExpire(prefix + ":events:c1").block()).isBetween(Duration.ofMinutes(25),
				Duration.ofMinutes(30));

		ReactiveRedisEventStore restarted = new ReactiveRedisEventStore(redis, prefix, Duration.ofSeconds(3),
				Duration.ofMinutes(30), false, 10, false);
		assertThat(restarted.hydrate().block()).isEqualTo(1);
		assertThat(restarted.find(id).blockFirst()).isInstanceOf(InstanceRegisteredEvent.class);
	}

}
