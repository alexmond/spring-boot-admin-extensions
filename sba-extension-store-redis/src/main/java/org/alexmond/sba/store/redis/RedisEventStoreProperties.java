package org.alexmond.sba.store.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Store-specific config for the Redis SBA event store. The Redis <em>connection</em> uses the
 * standard {@code spring.data.redis.*} properties (host/port/password/ssl); this only covers what's
 * specific to the store. Activated by {@code sba.eventstore.type=redis}.
 */
@ConfigurationProperties(prefix = "sba.eventstore.redis")
public class RedisEventStoreProperties {

	/** Key prefix for the store's Redis keys ({@code <prefix>:events:<id>}, {@code <prefix>:instances}). */
	private String keyPrefix = "sba:eventstore";

	public String getKeyPrefix() {
		return this.keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

}
