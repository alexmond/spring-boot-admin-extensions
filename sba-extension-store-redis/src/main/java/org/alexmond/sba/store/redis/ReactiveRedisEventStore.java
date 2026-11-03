package org.alexmond.sba.store.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
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
 *       so the registry survives an admin restart. By default it <em>skips discovery-sourced</em>
 *       instances (SBA's DiscoveryClient re-registers the live ones and prunes the rest), so it never
 *       resurrects dead instances as ghosts; live discovery wins any version conflict.</li>
 * </ul>
 *
 * <p>Storage: one sorted set per instance, {@code <prefix>:events:<instanceId>}, scored by event
 * version, member = Base64 of the JDK-serialized {@link InstanceEvent}; plus a set
 * {@code <prefix>:instances} of known instance ids. Each event key carries a refreshed TTL (see
 * {@code eventTtl}) so an instance that stops being written self-evicts — the mirror doesn't
 * accumulate stale ids across restarts/re-registrations. The TTL is <em>not</em> a retention limit.
 *
 * <p><b>Retention.</b> Redis mirrors the in-memory log, which SBA bounds by <em>compaction</em>: once
 * an instance's log exceeds {@code maxLogSizePerInstance}, only the newest event of each type (and of
 * each status) is kept, and {@code pruneInfoUpdatedEvents} keeps only the newest INFO_UPDATED. This is
 * deliberately not "keep the newest N" — replay needs the instance's {@code InstanceRegisteredEvent},
 * which is the oldest event. Each mirror write is one atomic script on the instance's key: normally an
 * idempotent {@code ZADD} of the new events, but a full <em>replace</em> with the in-memory log when
 * that log shrank (compaction/prune), on the first write for an instance in this process, and on the
 * write after a failed one. Replacing also repairs any gap Redis picked up (an outage, a restart from
 * an older snapshot, a log left over from an earlier registration). The script touches one key only,
 * so it is safe on Redis Cluster.
 *
 * <p><strong>Security:</strong> events are JDK-serialized (like SBA's {@code HazelcastEventStore}).
 * JDK deserialization of untrusted bytes is an RCE vector, so the Redis MUST be trusted:
 * authenticated + network-restricted to the admin (the deploy enforces requirepass + a
 * NetworkPolicy). Do not point this at a shared/open Redis.
 */
public class ReactiveRedisEventStore extends InMemoryEventStore {

	private static final Logger log = LoggerFactory.getLogger(ReactiveRedisEventStore.class);

	/** SBA's registration source for instances found via a DiscoveryClient (InstanceDiscoveryListener). */
	private static final String DISCOVERY_SOURCE = "discovery";

	/** SBA's own default ({@code InMemoryEventStore()}). */
	static final int DEFAULT_MAX_LOG_SIZE_PER_INSTANCE = 100;

	static final String MODE_APPEND = "append";

	static final String MODE_REPLACE = "replace";

	/**
	 * Writes one instance's events. KEYS[1] = event key; ARGV[1] = mode ({@code append} | {@code replace}),
	 * ARGV[2] = TTL in ms (&lt;= 0: none), then score/member pairs. {@code replace} drops the key first so
	 * it ends up holding exactly the given events.
	 */
	static final RedisScript<Long> WRITE_SCRIPT = RedisScript.of("""
			if ARGV[1] == 'replace' then redis.call('DEL', KEYS[1]) end
			for i = 3, #ARGV, 2 do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1]) end
			local ttl = tonumber(ARGV[2])
			if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end
			return redis.call('ZCARD', KEYS[1])
			""", Long.class);

	private final ReactiveStringRedisTemplate redis;

	private final String instancesKey;

	private final String eventKeyPrefix;

	private final Duration timeout;

	private final Duration eventTtl;

	private final boolean hydrateDiscovered;

	/**
	 * Instances whose Redis key this process has already brought in line with memory. An instance not
	 * in here gets a full replace on its next write; a failed write removes it again.
	 */
	private final Set<InstanceId> synced = ConcurrentHashMap.newKeySet();

	// --- persistence status (read by the health indicator) ---
	private volatile boolean redisReachable;

	private volatile String lastError;

	private volatile Instant lastSuccessAt;

	private volatile Instant lastFailureAt;

	private final AtomicLong mirrorSuccesses = new AtomicLong();

	private final AtomicLong mirrorFailures = new AtomicLong();

	public ReactiveRedisEventStore(ReactiveStringRedisTemplate redis, String keyPrefix) {
		this(redis, keyPrefix, Duration.ofSeconds(3), Duration.ofHours(24), false);
	}

	public ReactiveRedisEventStore(ReactiveStringRedisTemplate redis, String keyPrefix, Duration timeout,
			Duration eventTtl, boolean hydrateDiscovered) {
		this(redis, keyPrefix, timeout, eventTtl, hydrateDiscovered, DEFAULT_MAX_LOG_SIZE_PER_INSTANCE, false);
	}

	/**
	 * @param maxLogSizePerInstance compaction threshold for each instance's log, in memory and in Redis
	 * (SBA's {@code maxLogSizePerAggregate}); must be positive
	 * @param pruneInfoUpdatedEvents keep only the newest INFO_UPDATED event per instance (SBA's
	 * {@code spring.boot.admin.event-store.prune-info-updated-events})
	 */
	public ReactiveRedisEventStore(ReactiveStringRedisTemplate redis, String keyPrefix, Duration timeout,
			Duration eventTtl, boolean hydrateDiscovered, int maxLogSizePerInstance, boolean pruneInfoUpdatedEvents) {
		super(requirePositive(maxLogSizePerInstance), pruneInfoUpdatedEvents);
		this.redis = redis;
		this.instancesKey = keyPrefix + ":instances";
		this.eventKeyPrefix = keyPrefix + ":events:";
		this.timeout = timeout;
		this.eventTtl = eventTtl;
		this.hydrateDiscovered = hydrateDiscovered;
	}

	private static int requirePositive(int maxLogSizePerInstance) {
		if (maxLogSizePerInstance <= 0) {
			throw new IllegalArgumentException("maxLogSizePerInstance must be positive, was " + maxLogSizePerInstance);
		}
		return maxLogSizePerInstance;
	}

	@Override
	public Mono<Void> append(List<InstanceEvent> events) {
		if (events == null || events.isEmpty()) {
			return super.append(events);
		}
		InstanceId id = events.get(0).getInstance();
		// In-memory is authoritative: commit (optimistic-locked) + publish first, then mirror. Reading
		// the log size around the append (all in memory, non-blocking) tells us whether SBA compacted.
		return find(id).count()
			.flatMap((before) -> super.append(events)
				.then(find(id).collectList())
				.doOnNext((after) -> mirror(id, events, after, after.size() < before + events.size())))
			.then();
	}

	/** Fire-and-forget, non-blocking write-behind to Redis. Never fails the append. */
	private void mirror(InstanceId id, List<InstanceEvent> appended, List<InstanceEvent> snapshot,
			boolean shrank) {
		// Replace when memory dropped events, or when this process hasn't synced the key yet.
		boolean replace = this.synced.add(id) || shrank;
		redisWrite(id, replace ? snapshot : appended, replace)
			.doOnSuccess((v) -> markReachable(true, null, this.mirrorSuccesses))
			.onErrorResume((ex) -> {
				this.synced.remove(id); // the key may now have a gap: replace it on the next write
				markReachable(false, ex, this.mirrorFailures);
				log.warn("Redis mirror failed (registry unaffected; served from memory): {}", ex.toString());
				return Mono.empty();
			})
			.subscribe();
	}

	private Mono<Void> redisWrite(InstanceId id, List<InstanceEvent> events, boolean replace) {
		List<String> args = new ArrayList<>(2 + 2 * events.size());
		args.add(replace ? MODE_REPLACE : MODE_APPEND);
		args.add(Long.toString(ttlEnabled() ? this.eventTtl.toMillis() : 0L));
		for (InstanceEvent event : events) {
			args.add(Long.toString(event.getVersion()));
			args.add(serialize(event));
		}
		// One atomic script on the instance's key (ZADD or replace, then refresh the TTL so a live
		// instance's key stays fresh while a dead one self-evicts); SADD tracks known ids. The SADD is a
		// separate command so the script only touches one key (Redis Cluster safe).
		return this.redis.execute(WRITE_SCRIPT, List.of(this.eventKeyPrefix + id.getValue()), args)
			.then(this.redis.opsForSet().add(this.instancesKey, id.getValue()))
			.timeout(this.timeout)
			.then();
	}

	private boolean ttlEnabled() {
		return this.eventTtl != null && !this.eventTtl.isZero() && !this.eventTtl.isNegative();
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
				// Don't resurrect discovery-sourced instances (unless explicitly enabled): SBA's
				// InstanceDiscoveryListener re-registers the currently-live ones on startup and its
				// removeStaleInstances() deregisters the rest, so replaying them would just show dead
				// instances (e.g. old k8s pod IPs) as ghosts until the next discovery scan. Instances
				// discovery will NOT restore (client self-registrations) are always hydrated.
				if (!this.hydrateDiscovered && isDiscoverySourced(ordered)) {
					return Mono.just(0);
				}
				InstanceId id = ordered.get(0).getInstance();
				return super.append(ordered)
					.then(find(id).collectList())
					.doOnNext((kept) -> {
						// SBA compacted the replayed log (e.g. a key written before Redis was trimmed):
						// shrink the key to match, now, rather than waiting for the instance's next write.
						if (kept.size() < ordered.size()) {
							mirror(id, kept, kept, true);
						}
					})
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
				.map(this::deserialize)
				// ZSET gone (TTL-expired) but the id still lingers in the set → prune the stale member.
				.switchIfEmpty(this.redis.opsForSet().remove(this.instancesKey, id).thenMany(Flux.empty())))
			.timeout(this.timeout);
	}

	/** True if the latest registration in the log came from a DiscoveryClient (source="discovery"). */
	private static boolean isDiscoverySourced(List<InstanceEvent> events) {
		for (int i = events.size() - 1; i >= 0; i--) {
			if (events.get(i) instanceof InstanceRegisteredEvent registered) {
				Registration registration = registered.getRegistration();
				return registration != null && DISCOVERY_SOURCE.equals(registration.getSource());
			}
		}
		return false;
	}

	/** Lightweight reachability probe for the health indicator (updates the tracked status). */
	public Mono<Boolean> ping() {
		return this.redis.hasKey(this.instancesKey)
			.timeout(this.timeout)
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
