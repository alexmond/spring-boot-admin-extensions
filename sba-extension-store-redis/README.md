# sba-extension-store-redis

A Redis-backed `InstanceEventStore` for Spring Boot Admin that keeps the registry across admin
restarts **without** making Redis a single point of failure.

- The in-memory log stays authoritative. Reads never touch Redis.
- Every write is mirrored to Redis in the background (reactive, non-blocking).
- A Redis outage never blanks the registry and never fails a write. It shows up as a `DEGRADED`
  health status instead.
- On startup the store replays the mirrored log back into memory.

## Install

Add it to your Spring Boot Admin **server**, then opt in:

```xml
<dependency>
    <groupId>org.alexmond</groupId>
    <artifactId>sba-extension-store-redis</artifactId>
    <version>4.1.1.1</version>
</dependency>
```

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD}

sba:
  eventstore:
    type: redis
```

Without `sba.eventstore.type=redis` the jar does nothing and Spring Boot Admin keeps its default
in-memory store.

## Properties

All are optional. Prefix: `sba.eventstore.redis`.

| Property | Default | Meaning |
|---|---|---|
| `key-prefix` | `sba:eventstore` | Redis key namespace: `<prefix>:events:<instanceId>` and `<prefix>:instances`. |
| `timeout` | `3s` | Timeout for each Redis command (mirror, hydrate, ping). |
| `event-ttl` | `24h` | Time-to-live of an instance's key, refreshed on every write, so instances that stop reporting drop out. `0` or negative means never expire. |
| `hydrate-on-startup` | `true` | Replay the mirrored log into memory when the admin starts. |
| `hydrate-discovered` | `false` | Also replay instances that came from a `DiscoveryClient`. Off by default: discovery re-registers the live ones itself, so replaying them only brings back dead instances. |

## Health

The store adds an `eventStorePersistence` health indicator. It reports `UP` while Redis is
reachable and `DEGRADED` (HTTP 200) while it is not, with the time and text of the last error.
`DEGRADED` is deliberate: the admin is still serving from memory, so it must not be restarted or
taken out of rotation. Keep this indicator out of liveness and readiness groups.

Spring Boot also registers its generic Redis health check once Spring Data Redis is on the
classpath, and that one reports `DOWN` during an outage. Switch it off:

```yaml
management:
  health:
    redis:
      enabled: false
```

## Security

Events are stored with JDK serialization. Use only an authenticated Redis that nothing else can
write to. See [SECURITY.md](../SECURITY.md).

## Tests

`./mvnw -pl sba-extension-store-redis verify` runs the unit tests (no Redis needed). To also run
the integration test against a real Redis:

```bash
docker run -d --rm -p 16399:6379 redis:7-alpine redis-server --requirepass pw
SBA_REDIS_TEST_URI=redis://:pw@localhost:16399 ./mvnw -pl sba-extension-store-redis verify
```
