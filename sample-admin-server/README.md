# sample-admin-server

A runnable Spring Boot Admin server that wires all three extensions in this repo, for local/manual
testing. Not a published artifact.

## Run

```bash
./mvnw -pl sample-admin-server -am spring-boot:run
```

`-am` builds the sibling extension modules first (including their Vue UI bundles). Then open
<http://localhost:8080>.

The server self-registers as its own SBA client, so the registry shows one instance
(`sample-admin-server`) right away — enough to exercise the UI extensions:

- **Environments** (top-level nav) — the Applications view grouped by `info.tags.environment`; this
  instance is tagged `dev` (see `application.yml`).
- **Live Metrics** (per-instance, under *Insights*, below *Metrics*) — pick a metric, Add graph.

## Testing the Redis event store

The Redis store (`sba-extension-store-redis`) is **off by default** so the server runs with no
external dependencies. To turn it on:

1. `docker compose up -d` (in this directory) — starts an authenticated Redis on `localhost:6379`.
2. In `src/main/resources/application.yml`, uncomment the `spring.data.redis` block and the
   `sba.eventstore` block.
3. Restart. Check `http://localhost:8080/actuator/health` — `eventStorePersistence` should report
   `UP`. Stop Redis (`docker compose stop`) and it flips to the custom `DEGRADED` status (HTTP 200,
   registry still served from memory).
