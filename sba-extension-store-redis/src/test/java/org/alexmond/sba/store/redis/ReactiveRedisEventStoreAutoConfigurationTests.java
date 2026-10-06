package org.alexmond.sba.store.redis;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import de.codecentric.boot.admin.server.eventstore.InstanceEventStore;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveRedisEventStoreAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ReactiveRedisEventStoreAutoConfiguration.class))
		.withUserConfiguration(RedisTemplateConfig.class);

	@Test
	void staysDormantUnlessOptedIn() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(ReactiveRedisEventStore.class)
			.doesNotHaveBean(EventStorePersistenceHealthIndicator.class));
		this.runner.withPropertyValues("sba.eventstore.type=hazelcast")
			.run((context) -> assertThat(context).doesNotHaveBean(ReactiveRedisEventStore.class));
	}

	@Test
	void registersStoreHealthIndicatorAndHydratorWhenOptedIn() {
		this.runner.withPropertyValues("sba.eventstore.type=redis").run((context) -> {
			assertThat(context).hasSingleBean(InstanceEventStore.class)
				.hasSingleBean(ReactiveRedisEventStore.class)
				.hasSingleBean(EventStorePersistenceHealthIndicator.class)
				.hasBean("redisEventStoreHydrator");
			RedisEventStoreProperties props = context.getBean(RedisEventStoreProperties.class);
			assertThat(props.keyPrefix()).isEqualTo("sba:eventstore");
			assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(3));
			assertThat(props.eventTtl()).isEqualTo(Duration.ofHours(24));
			assertThat(props.hydrateOnStartup()).isTrue();
			assertThat(props.hydrateDiscovered()).isFalse();
		});
	}

	@Test
	void bindsCustomProperties() {
		this.runner
			.withPropertyValues("sba.eventstore.type=redis", "sba.eventstore.redis.key-prefix=custom",
					"sba.eventstore.redis.timeout=1s", "sba.eventstore.redis.event-ttl=0",
					"sba.eventstore.redis.hydrate-discovered=true")
			.run((context) -> {
				RedisEventStoreProperties props = context.getBean(RedisEventStoreProperties.class);
				assertThat(props.keyPrefix()).isEqualTo("custom");
				assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(1));
				assertThat(props.eventTtl()).isEqualTo(Duration.ZERO);
				assertThat(props.hydrateDiscovered()).isTrue();
			});
	}

	@Test
	void hydrationOnStartupCanBeSwitchedOff() {
		this.runner.withPropertyValues("sba.eventstore.type=redis", "sba.eventstore.redis.hydrate-on-startup=false")
			.run((context) -> assertThat(context).hasSingleBean(ReactiveRedisEventStore.class)
				.doesNotHaveBean("redisEventStoreHydrator"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void theStartupHydratorSurvivesAnUnreachableRedis() {
		FakeRedis redis = new FakeRedis();
		redis.down = true;
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ReactiveRedisEventStoreAutoConfiguration.class))
			.withBean(ReactiveStringRedisTemplate.class, () -> redis.template)
			.withPropertyValues("sba.eventstore.type=redis")
			.run((context) -> {
				ApplicationListener<ApplicationReadyEvent> hydrator = context.getBean("redisEventStoreHydrator",
						ApplicationListener.class);
				hydrator.onApplicationEvent(null); // must not throw
				assertThat(context.getBean(ReactiveRedisEventStore.class).persistenceStatus().reachable()).isFalse();
			});
	}

	@Test
	void backsOffWhenTheApplicationDefinesItsOwnStore() {
		this.runner.withPropertyValues("sba.eventstore.type=redis")
			.withBean("customStore", InstanceEventStore.class, InMemoryEventStore::new)
			.run((context) -> assertThat(context).hasSingleBean(InstanceEventStore.class)
				.doesNotHaveBean(ReactiveRedisEventStore.class));
	}

	@Test
	void needsAReactiveRedisTemplate() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ReactiveRedisEventStoreAutoConfiguration.class))
			.withPropertyValues("sba.eventstore.type=redis")
			.run((context) -> assertThat(context).doesNotHaveBean(ReactiveRedisEventStore.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class RedisTemplateConfig {

		@Bean
		ReactiveStringRedisTemplate reactiveStringRedisTemplate() {
			return new FakeRedis().template;
		}

	}

}
