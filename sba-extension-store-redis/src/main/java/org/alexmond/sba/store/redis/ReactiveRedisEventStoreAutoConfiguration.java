package org.alexmond.sba.store.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration;
import de.codecentric.boot.admin.server.eventstore.InstanceEventStore;

/**
 * Registers the resilient {@link ReactiveRedisEventStore} as the SBA {@link InstanceEventStore} when
 * {@code sba.eventstore.type=redis}. Runs after Boot's reactive-Redis auto-config (which supplies
 * the {@link ReactiveStringRedisTemplate} from {@code spring.data.redis.*}) and before
 * {@code AdminServerAutoConfiguration}; with {@code @ConditionalOnMissingBean(InstanceEventStore)}
 * it replaces SBA's default in-memory store.
 *
 * <p>Also wires (a) an {@link ApplicationReadyEvent} listener that hydrates the in-memory log from
 * Redis once on startup (async, non-blocking), and (b) the {@link EventStorePersistenceHealthIndicator}
 * so a Redis outage is visible as {@code DEGRADED} without restarting the admin.
 */
@AutoConfiguration(after = DataRedisReactiveAutoConfiguration.class)
@ConditionalOnClass({ ReactiveStringRedisTemplate.class, InstanceEventStore.class })
@ConditionalOnProperty(prefix = "sba.eventstore", name = "type", havingValue = "redis")
@AutoConfigureBefore(AdminServerAutoConfiguration.class)
@EnableConfigurationProperties(RedisEventStoreProperties.class)
public class ReactiveRedisEventStoreAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ReactiveRedisEventStoreAutoConfiguration.class);

	@Bean
	@ConditionalOnBean(ReactiveStringRedisTemplate.class)
	@ConditionalOnMissingBean(InstanceEventStore.class)
	public ReactiveRedisEventStore instanceEventStore(ReactiveStringRedisTemplate redis,
			RedisEventStoreProperties props) {
		return new ReactiveRedisEventStore(redis, props.getKeyPrefix());
	}

	/**
	 * Replay the persisted event log into memory once the app is ready (non-blocking, best-effort).
	 * Kept off the startup path so a slow/down Redis can never delay readiness.
	 */
	@Bean
	@ConditionalOnBean(ReactiveRedisEventStore.class)
	public ApplicationListener<ApplicationReadyEvent> redisEventStoreHydrator(ReactiveRedisEventStore store) {
		return (event) -> store.hydrate()
			.subscribe((n) -> log.info("SBA event store: hydrated {} instance(s) from Redis", n),
					(ex) -> log.warn("SBA event store: Redis hydration failed", ex));
	}

	@Bean
	@ConditionalOnClass(ReactiveHealthIndicator.class)
	@ConditionalOnBean(ReactiveRedisEventStore.class)
	public EventStorePersistenceHealthIndicator eventStorePersistenceHealthIndicator(
			ReactiveRedisEventStore store) {
		return new EventStorePersistenceHealthIndicator(store);
	}

}
