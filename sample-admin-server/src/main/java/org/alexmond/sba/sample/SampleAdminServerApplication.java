package org.alexmond.sba.sample;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal Spring Boot Admin server used to exercise the extensions in this repo by
 * hand.
 *
 * <p>
 * It depends on all three extension modules, so the two UI extensions (Environments, Live
 * Metrics) are served from the classpath automatically, and the Redis event store is
 * available but dormant until {@code sba.eventstore.type=redis} is set. The app also
 * self-registers as its own SBA client (see {@code application.yml}) so the registry has
 * a live instance to browse.
 */
@EnableAdminServer
@SpringBootApplication
// Instantiated by Spring as a configuration class, so not a utility class.
@SuppressWarnings("PMD.UseUtilityClass")
public class SampleAdminServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SampleAdminServerApplication.class, args);
	}

}
