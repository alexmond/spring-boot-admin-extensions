package org.alexmond.sba.store.redis;

import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Redis that accepts the command and never answers — what a network black hole looks
 * like.
 */
final class HangingRedis {

	final ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);

	HangingRedis() {
		when(this.template.hasKey(anyString())).thenReturn(Mono.never());
	}

}
