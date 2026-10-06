# spring-boot-admin-extensions

A small collection of extensions for [Spring Boot Admin](https://github.com/codecentric/spring-boot-admin)
(SBA) — two custom UI pages and one resilient backend event store. Each is an independent module;
add the ones you want to your SBA **server**'s classpath.

> Not a flagship project — a handful of extensions extracted from a home setup because they're
> generally useful. Built against **Spring Boot 4.1 / Spring Boot Admin 4.1**.

## Modules

| Module | Type | What it does |
|---|---|---|
| [`sba-extension-environments`](sba-extension-environments/) | UI | An **Environments** page: the standard Applications view with an extra outer accordion grouping apps by their `info.tags.environment` (environment → application → node). |
| [`sba-extension-live-metrics`](sba-extension-live-metrics/) | UI | A **Live Metrics** page: renders each selected metric as a live, rolling time-series line chart (like the Overview CPU/Memory graphs) instead of a one-shot value. Graphs poll on an interval, can be removed, and persist across reloads via `localStorage`. |
| [`sba-extension-store-redis`](sba-extension-store-redis/) | backend / SPI | A resilient Redis-backed `InstanceEventStore`. In-memory is authoritative; Redis is a fully reactive (Lettuce) write-behind mirror for durability. A Redis outage never blanks the registry and never fails a write — it surfaces as a non-fatal `DEGRADED` health status. Hydrates from Redis on startup. |

The two UI modules ship as resource jars carrying a built Vue bundle under
`META-INF/spring-boot-admin-server-ui/extensions/`; SBA serves them from the classpath automatically —
there's nothing to wire, just add the dependency.

## Versions

The version tracks the Spring Boot version it is built against: `<boot-version>.<revision>`.
Pick the line that matches your Spring Boot Admin server.

| Extensions | Spring Boot | Spring Boot Admin | Branch |
|---|---|---|---|
| `4.1.1.x` | 4.1.x | 4.1.3 | `main` |
| `4.0.8.x` | 4.0.x | 4.0.4 | `4.0` |

Spring Boot Admin `4.0.x` has no release that fixes
[GHSA-4jg4-pqcq-xf3x](https://github.com/codecentric/spring-boot-admin/security/advisories/GHSA-4jg4-pqcq-xf3x)
(stored XSS). Use the 4.1 line if you need that fix.

## Using it

Add the module(s) to your Spring Boot Admin **server** application:

```xml
<dependency>
    <groupId>org.alexmond</groupId>
    <artifactId>sba-extension-live-metrics</artifactId>
    <version>4.1.1.1</version>
</dependency>
```

### `sba-extension-store-redis` config

The Redis connection uses the standard `spring.data.redis.*` properties. The store is **opt-in**:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      # password / ssl / etc. as usual

sba:
  eventstore:
    type: redis                  # activates the Redis-backed store (default is SBA's in-memory)
    redis:                       # all optional; values shown are the defaults
      key-prefix: sba:eventstore # keys are <prefix>:events:<id>, <prefix>:instances
      timeout: 3s                # per-command Redis timeout (mirror / hydrate / ping)
      event-ttl: 24h             # TTL per instance key, refreshed on write; stale ids self-evict
                                 #   (0/negative = never expire) — stops ghost buildup when instances
                                 #   re-register under new ids (k8s pod restarts, autoscaling)
      hydrate-on-startup: true   # replay the persisted log into memory on startup
      hydrate-discovered: false  # skip discovery-sourced instances on hydrate (a DiscoveryClient
                                 #   restores the live ones itself); true = restore everything
```

With `sba.eventstore.type` unset (or any other value) SBA keeps its default in-memory store, so the
dependency is safe to have on the classpath without turning it on. Every `redis.*` key is optional and
documented in the generated `spring-configuration-metadata.json` (IDE autocomplete).

## Build

```bash
./mvnw clean verify
```

Java 21. The frontend-maven-plugin downloads its own Node/npm to build the Vue UI modules — you don't
need Node on your `PATH`. The `store-redis` integration test only runs when `SBA_REDIS_TEST_URI` points
at a real Redis; otherwise it's skipped.

## License

[Apache-2.0](LICENSE).
