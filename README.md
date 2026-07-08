# spring-boot-admin-extensions

A small collection of extensions for [Spring Boot Admin](https://github.com/codecentric/spring-boot-admin)
(SBA) — two custom UI pages and one resilient backend event store. Each is an independent module;
add the ones you want to your SBA **server**'s classpath.

> Not a flagship project — a handful of extensions extracted from a home setup because they're
> generally useful. Built against **Spring Boot 4.0 / Spring Boot Admin 4.0**.

## Modules

| Module | Type | What it does |
|---|---|---|
| [`sba-extension-environments`](sba-extension-environments/) | UI | An **Environments** page: the standard Applications view with an extra outer accordion grouping apps by their `info.tags.environment` (environment → application → node). |
| [`sba-extension-live-metrics`](sba-extension-live-metrics/) | UI | A **Live Metrics** page: renders each selected metric as a live, rolling time-series line chart (like the Overview CPU/Memory graphs) instead of a one-shot value. Graphs poll on an interval, can be removed, and persist across reloads via `localStorage`. |
| [`sba-extension-store-redis`](sba-extension-store-redis/) | backend / SPI | A resilient Redis-backed `InstanceEventStore`. In-memory is authoritative; Redis is a fully reactive (Lettuce) write-behind mirror for durability. A Redis outage never blanks the registry and never fails a write — it surfaces as a non-fatal `DEGRADED` health status. Hydrates from Redis on startup. |

The two UI modules ship as resource jars carrying a built Vue bundle under
`META-INF/spring-boot-admin-server-ui/extensions/`; SBA serves them from the classpath automatically —
there's nothing to wire, just add the dependency.

## Using it

Add the module(s) to your Spring Boot Admin **server** application:

```xml
<dependency>
    <groupId>org.alexmond</groupId>
    <artifactId>sba-extension-live-metrics</artifactId>
    <version>0.1.0</version>
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
    type: redis                 # activates the Redis-backed store (default is SBA's in-memory)
    redis:
      key-prefix: sba:eventstore # optional; keys are <prefix>:events:<id>, <prefix>:instances
```

With `sba.eventstore.type` unset (or any other value) SBA keeps its default in-memory store, so the
dependency is safe to have on the classpath without turning it on.

## Build

```bash
./mvnw clean verify
```

Java 21. The frontend-maven-plugin downloads its own Node/npm to build the Vue UI modules — you don't
need Node on your `PATH`. The `store-redis` integration test only runs when `SBA_REDIS_TEST_URI` points
at a real Redis; otherwise it's skipped.

## License

[Apache-2.0](LICENSE).
