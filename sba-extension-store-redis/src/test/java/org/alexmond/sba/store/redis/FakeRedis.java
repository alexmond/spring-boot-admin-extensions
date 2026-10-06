package org.alexmond.sba.store.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.data.redis.core.ZSetOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An in-memory stand-in for the handful of Redis commands {@link ReactiveRedisEventStore} uses
 * (ZADD / ZRANGE / SADD / SMEMBERS / SREM / EXPIRE / EXISTS), so the store's logic can be tested
 * without a Redis. Flip {@link #down} to make every command fail like an unreachable server.
 */
@SuppressWarnings("unchecked")
final class FakeRedis {

	final Map<String, TreeMap<Double, String>> zsets = new ConcurrentHashMap<>();

	final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

	final Map<String, Duration> ttls = new ConcurrentHashMap<>();

	volatile boolean down;

	final ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);

	FakeRedis() {
		ReactiveZSetOperations<String, String> zset = mock(ReactiveZSetOperations.class);
		ReactiveSetOperations<String, String> set = mock(ReactiveSetOperations.class);
		when(this.template.opsForZSet()).thenReturn(zset);
		when(this.template.opsForSet()).thenReturn(set);

		when(zset.addAll(anyString(), anyCollection())).thenAnswer((inv) -> mono(() -> {
			Collection<ZSetOperations.TypedTuple<String>> tuples = inv.getArgument(1);
			TreeMap<Double, String> target = this.zsets.computeIfAbsent(inv.getArgument(0), (k) -> new TreeMap<>());
			tuples.forEach((t) -> target.put(t.getScore(), t.getValue()));
			return (long) tuples.size();
		}));
		when(zset.range(anyString(), any(Range.class))).thenAnswer((inv) -> Flux.defer(() -> {
			if (this.down) {
				return Flux.error(unreachable());
			}
			return Flux.fromIterable(this.zsets.getOrDefault(inv.getArgument(0), new TreeMap<>()).values());
		}));
		when(set.add(anyString(), any(String[].class))).thenAnswer((inv) -> mono(() -> {
			Object[] args = inv.getArguments();
			Set<String> target = this.sets.computeIfAbsent((String) args[0], (k) -> new LinkedHashSet<>());
			long added = 0;
			for (int i = 1; i < args.length; i++) {
				added += target.add((String) args[i]) ? 1 : 0;
			}
			return added;
		}));
		when(set.members(anyString())).thenAnswer((inv) -> Flux.defer(() -> {
			if (this.down) {
				return Flux.error(unreachable());
			}
			return Flux.fromIterable(new LinkedHashSet<>(this.sets.getOrDefault(inv.getArgument(0), Set.of())));
		}));
		when(set.remove(anyString(), any(Object[].class))).thenAnswer((inv) -> mono(() -> {
			Object[] args = inv.getArguments();
			Set<String> target = this.sets.getOrDefault((String) args[0], new LinkedHashSet<>());
			long removed = 0;
			for (int i = 1; i < args.length; i++) {
				removed += target.remove(args[i]) ? 1 : 0;
			}
			return removed;
		}));
		when(this.template.expire(anyString(), any(Duration.class))).thenAnswer((inv) -> mono(() -> {
			this.ttls.put(inv.getArgument(0), inv.getArgument(1));
			return true;
		}));
		when(this.template.hasKey(anyString()))
			.thenAnswer((inv) -> mono(() -> this.sets.containsKey(inv.<String>getArgument(0))));
	}

	private <T> Mono<T> mono(Supplier<T> action) {
		return Mono.defer(() -> this.down ? Mono.error(unreachable()) : Mono.just(action.get()));
	}

	private static RedisConnectionFailureException unreachable() {
		return new RedisConnectionFailureException("Unable to connect to Redis (simulated outage)");
	}

}
