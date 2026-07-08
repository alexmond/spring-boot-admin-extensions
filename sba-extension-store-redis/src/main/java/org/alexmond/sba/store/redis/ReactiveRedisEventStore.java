package org.alexmond.sba.store.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import de.codecentric.boot.admin.server.eventstore.OptimisticLockingException;

import static java.util.Comparator.comparingLong;

/**
 * Resilient Redis-backed {@link de.codecentric.boot.admin.server.eventstore.InstanceEventStore} for
 * Spring Boot Admin. <strong>The in-memory event log is authoritative</strong> (this extends SBA's
 * {@link InMemoryEventStore}); Redis is a best-effort, fully reactive <em>write-behind mirror</em>
 * used for durability across restarts.
 *
 * <p>Why this shape (learned the hard way): a Redis outage must never blank the SBA registry, and a
 * blocking Redis call on a reactor thread deadlocks SBA. So:
 * <ul>
 *   <li><b>Reads</b> ({@code findAll}/{@code find}) are served entirely from memory — inherited from
 *       {@code InMemoryEventStore} — so they always work, even with Redis down.</li>
 *   <li><b>Writes</b> ({@link #append}) commit to memory first (authoritative, optimistic-locked,
 *       published), then fire a non-blocking Lettuce mirror to Redis as a side-effect. If the mirror
 *       fails, the append still succeeds; the failure is recorded (see {@link #persistenceStatus()})
 *       and surfaced by the {@code eventStorePersistence} health indicator — it never propagates.</li>
 *   <li><b>Startup</b> {@link #hydrate()} replays the persisted log back into memory (best-effort),
 *       so the registry survives an admin restart. Live discovery wins any version conflict.</li>
 * </ul>
 *
 * <p>Storage: one sorted set per instance, {@code <prefix>:events:<instanceId>}, scored by event
 * version, member = Base64 of the JDK-serialized {@link InstanceEvent}; plus a set
 * {@code <prefix>:instances} of known instance ids. The optimistic lock lives in the in-memory
 * store now, so the mirror is a plain idempotent {@code ZADD} (no Lua needed).
 *
 * <p><strong>Security:</strong> events are JDK-serialized (like SBA's {@code HazelcastEventStore}).
 * JDK deserialization of untrusted bytes is an RCE vector, so the Redis MUST be trusted:
 * authenticated + network-restricted to the admin (the deploy enforces requirepass + a
 * NetworkPolicy). Do not point this at a shared/open Redis.
 */
public class ReactiveRedisEventStore extends InMemoryEventStore {

	private static final Logger log = LoggerFactory.getLogger(ReactiveRedisEventStore.class);

	private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(3);

	private final ReactiveStringRedisTemplate redis;

	private final String instancesKey;

	private final String eventKeyPrefix;

	// --- persistence status (read by the health indicator) ---
	private volatile boolean redisReachable;

	private volatile String lastError;

	private volatile Instant lastSuccessAt;

	private volatile Instant lastFailureAt;

	private final AtomicLong mirrorSuccesses = new AtomicLong();

	private final AtomicLong mirrorFailures = new AtomicLong();

	public ReactiveRedisEventStore(ReactiveStringRedisTemplate redis, String keyPrefix) {
		super();
		this.redis = redis;
		this.instancesKey = keyPrefix + ":instances";
		this.eventKeyPrefix = keyPrefix + ":events:";
	}

	@Override
	public Mono<Void> append(List<InstanceEvent> events) {
		// In-memory is authoritative: commit (optimistic-locked) + publish first, then mirror.
		return super.append(events).doOnSuccess((v) -> mirror(events));
	}

	/** Fire-and-forget, non-blocking write-behind to Redis. Never fails the append. */
	private void mirror(List<InstanceEvent> events) {
		if (events == null || events.isEmpty()) {
			return;
		}
		redisWrite(events)
			.doOnSuccess((v) -> markReachable(true, null, this.mirrorSuccesses))
			.onErrorResume((ex) -> {
				markReachable(false, ex, this.mirrorFailures);
				log.warn("Redis mirror failed (registry unaffected; served from memory): {}", ex.toString());
				return Mono.empty();
			})
			.subscribe();
	}

	private Mono<Void> redisWrite(List<InstanceEvent> events) {
		InstanceId id = events.get(0).getInstance();
		Set<ZSetOperations.TypedTuple<String>> tuples = events.stream()
			.map((e) -> ZSetOperations.TypedTuple.of(serialize(e), (double) e.getVersion()))
			.collect(Collectors.toSet());
		return this.redis.opsForZSet()
			.addAll(this.eventKeyPrefix + id.getValue(), tuples)
			.then(this.redis.opsForSet().add(this.instancesKey, id.getValue()))
			.timeout(REDIS_TIMEOUT)
			.then();
	}

	/**
	 * Best-effort replay of the persisted log back into the in-memory store (called once on startup).
	 * Uses {@code super.append} so it repopulates + publishes to the registry WITHOUT re-mirroring to
	 * Redis; per-instance {@link OptimisticLockingException}s (live discovery already advanced that
	 * instance) are ignored. Returns the number of instances hydrated; 0 if Redis is unavailable.
	 */
	public Mono<Integer> hydrate() {
		return readAllFromRedis().collectMultimap(InstanceEvent::getInstance)
			.flatMapMany((byInstance) -> Flux.fromIterable(byInstance.values()))
			.flatMap((events) -> {
				List<InstanceEvent> ordered = events.stream()
					.sorted(comparingLong(InstanceEvent::getVersion))
					.toList();
				return super.append(ordered)
					.thenReturn(1)
					.onErrorResume(OptimisticLockingException.class, (ex) -> Mono.just(0));
			})
			.reduce(0, Integer::sum)
			.doOnSuccess((n) -> markReachable(true, null, null))
			.onErrorResume((ex) -> {
				markReachable(false, ex, null);
				log.warn("Redis hydration skipped (starting from empty; discovery will refill): {}", ex.toString());
				return Mono.just(0);
			});
	}

	private Flux<InstanceEvent> readAllFromRedis() {
		return this.redis.opsForSet()
			.members(this.instancesKey)
			.flatMap((id) -> this.redis.opsForZSet()
				.range(this.eventKeyPrefix + id, Range.closed(0L, -1L))
				.map(this::deserialize))
			.timeout(REDIS_TIMEOUT);
	}

	/** Lightweight reachability probe for the health indicator (updates the tracked status). */
	public Mono<Boolean> ping() {
		return this.redis.hasKey(this.instancesKey)
			.timeout(REDIS_TIMEOUT)
			.map((exists) -> true)
			.doOnNext((ok) -> markReachable(true, null, null))
			.onErrorResume((ex) -> {
				markReachable(false, ex, null);
				return Mono.just(false);
			});
	}

	public PersistenceStatus persistenceStatus() {
		return new PersistenceStatus(this.redisReachable, this.mirrorSuccesses.get(), this.mirrorFailures.get(),
				this.lastSuccessAt, this.lastFailureAt, this.lastError);
	}

	private void markReachable(boolean reachable, Throwable error, AtomicLong counter) {
		this.redisReachable = reachable;
		if (counter != null) {
			counter.incrementAndGet();
		}
		if (reachable) {
			this.lastSuccessAt = Instant.now();
			this.lastError = null;
		}
		else {
			this.lastFailureAt = Instant.now();
			this.lastError = (error != null) ? error.toString() : "unknown";
		}
	}

	private String serialize(InstanceEvent event) {
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
				ObjectOutputStream oos = new ObjectOutputStream(bos)) {
			oos.writeObject(event);
			oos.flush();
			return Base64.getEncoder().encodeToString(bos.toByteArray());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not serialize " + event, ex);
		}
	}

	private InstanceEvent deserialize(String member) {
		byte[] bytes = Base64.getDecoder().decode(member);
		try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
			return (InstanceEvent) ois.readObject();
		}
		catch (IOException | ClassNotFoundException ex) {
			throw new IllegalStateException("Could not deserialize event from Redis", ex);
		}
	}

	/** Immutable snapshot of the Redis mirror's health, surfaced via the health indicator. */
	public record PersistenceStatus(boolean reachable, long mirrorSuccesses, long mirrorFailures,
			Instant lastSuccessAt, Instant lastFailureAt, String lastError) {
	}

}
