# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A multi-module Maven project of independent extensions for [Spring Boot Admin](https://github.com/codecentric/spring-boot-admin) (SBA). Each module is meant to be dropped onto an SBA **server**'s classpath on its own. Built against **Spring Boot 4.1 / SBA 4.1** (Java 21).

Three modules, two shapes:
- `sba-extension-environments`, `sba-extension-live-metrics` — **UI extensions**. Vite/Vue 3 bundles shipped inside a resource jar; SBA serves them from the classpath, no wiring.
- `sba-extension-store-redis` — **backend/SPI extension**. A Spring Boot auto-configuration that replaces SBA's `InstanceEventStore`.

## Build & test

```bash
./mvnw clean verify          # full reactor build (all modules + UI bundles)
./mvnw -pl sba-extension-store-redis verify   # single module
```

- **Java 21** required. Do not add `cd` before `./mvnw` — pass module paths / `-f` instead.
- The `frontend-maven-plugin` downloads its own Node/npm (see `node.version` in the root `pom.xml`) and runs `npm ci` + `npm run build`. **You do not need Node on your PATH**, and don't run `npm install` in a UI module by hand — the Maven build owns the frontend lifecycle and `npm ci` installs reproducibly from the committed `package-lock.json`.
- **Tests** live only in `sba-extension-store-redis`. Unit tests (`*Tests`, surefire) use `FakeRedis`, an in-memory stand-in for the few Redis commands the store issues, so they need no Redis and carry the **80% JaCoCo line gate**. Flip `FakeRedis.down` to simulate an outage — that is how the "never blank the registry, never fail a write" promise is pinned. Add a command to `FakeRedis` when the store starts using a new one.
- **Redis integration test** (`ReactiveRedisEventStoreIT`, failsafe) is gated by `@EnabledIfEnvironmentVariable(SBA_REDIS_TEST_URI)` — skipped unless that env var points at a real, authenticated Redis. To run it: `docker run -d --rm -p 16399:6379 redis:7-alpine redis-server --requirepass pw`, then `SBA_REDIS_TEST_URI=redis://:pw@localhost:16399 ./mvnw -pl sba-extension-store-redis verify`. A test class named `*IT` only runs because failsafe is bound in that module's POM; surefire ignores the name.
- The UI modules have no automated tests. After any UI or Spring Boot Admin change, run `sample-admin-server` and check both extensions in a browser (navigation click **and** direct deep link, no page errors).
- CI (`.github/workflows/build.yml`) runs `./mvnw -B --no-transfer-progress verify` on JDK 21 and 25, with a Redis container so the IT runs; a step fails the build if the IT was skipped.
- Every admin page logs one `404` for `/mcp`. That is Spring Boot Admin's own UI probing for its MCP feature, not these extensions.

## Versioning & branches

Follows the machine-wide **Spring Boot extension** rule (see global CLAUDE.md): the project version tracks the Boot version it builds against (currently `4.1.1.1-SNAPSHOT` against Boot `4.1.1` / SBA `4.1.3` — i.e. `<boot-version>.<n>`). `master`/`main` tracks the latest Boot minor; older Boot lines get their own named branches. This is the one project where the numeric-only release rule does **not** apply — match the Boot-aligned scheme.

## Releasing

Run the **`release-prep`** skill before tagging. `.github/workflows/maven_release.yml` (manual
dispatch) takes `branch` / `releaseVersion` / `nextVersion`: it sets the version, updates the README
install snippet, runs `verify`, tags (**no `v` prefix**), deploys to Maven Central with `-Prelease`
and opens a GitHub release. Release the current line from `main`, older lines from their branch.

- `-Prelease` signs and attaches `-sources` / `-javadoc` jars. The two UI modules have no Java, so
  their module POMs build a sources jar from the Vue sources and a **placeholder javadoc jar** from
  `src/javadoc/` — Maven Central rejects a jar artifact without both.
- `sample-admin-server` is kept off Central by `excludeArtifacts` in the root POM.
- Local check of the release build, without signing or publishing:
  `./mvnw clean verify -Prelease -Dgpg.skip`, then confirm each published module has
  `-sources.jar` and `-javadoc.jar` in `target/`. This does not build the Central bundle, so it
  cannot prove what gets uploaded.
- Repository secrets (`OSSRH_*`, `GPG_*`) are provisioned from the infra repo, not set by hand.

## UI extension architecture (environments, live-metrics)

Both UI modules follow the same pattern; use one as a template for the other.

- **Entry** `src/index.js` calls `SBA.use({ install({ viewRegistry, i18n }) {...} })` to register a view. `SBA` is a global provided by the host at load time (`/* global SBA */`). i18n strings are merged via `i18n.mergeLocaleMessage`.
  - `environments` registers a **top-level page** (`path: /environments`, its own nav group + icon).
  - `live-metrics` registers a **per-instance view** (`parent: "instances"`, `group: "insights"`, `isEnabled` gated on `instance.hasEndpoint("metrics")`), ordered 51 to sit right under the built-in Metrics view (50).
- **Build** `vite.config.js` emits a **UMD** bundle to `target/dist/<name>-ui.js`. Vue is **externalized** — `external: ["vue"]`, `globals: { vue: "Vue" }` — because the SBA server provides the Vue runtime as the global `Vue`. Never bundle Vue.
- **Packaging** the module `pom.xml` uses `maven-resources-plugin` (phase `process-classes`) to copy `target/dist` into `target/classes/META-INF/spring-boot-admin-server-ui/extensions/<name>/`. That classpath location is the contract with SBA — the `<name>` segment must match the folder SBA is told to load. A `routes.txt` under the same resources path declares the SPA routes the extension owns.
- **Reusing SBA components**: `.vue` views use SBA's globally-registered primitives (`sba-panel`, `sba-status`, `sba-tags`, `sba-button`, `font-awesome-icon`, …) and its data stores (`SBA.useApplicationStore()`). SBA's internal view components are **not importable** from an extension, so row markup is deliberately mirrored/copied from SBA's own views rather than imported. The frontend instance model exposes `instance.tags` (the server flattens `info.tags.*`); there is no `info` object on the frontend.
- **Keeping the copied markup in sync**: because the row/hero markup is copied from SBA's internal views (it can't be imported), it can silently drift when the pinned SBA version changes. When bumping `spring-boot-admin.version` (and `node.version`, which is matched to SBA's own build), diff the corresponding SBA source views against these `.vue` files and re-sync — the extensions won't fail to build on drift, they'll just render stale/wrong. The affected views today: `environments.vue` (mirrors SBA's `views/applications` + `ApplicationStatusHero`) and `live-metrics.vue` (mirrors the Metrics view).

## Redis event store architecture (sba-extension-store-redis)

The central design invariant: **a Redis outage must never blank the SBA registry and never fail a write.** Read the class Javadoc in `ReactiveRedisEventStore` before changing anything here — it documents why the shape is what it is.

- `ReactiveRedisEventStore extends InMemoryEventStore`. **In-memory is authoritative.** Reads (`find`/`findAll`) and the optimistic lock are inherited and served from memory, so they always work. `append` commits to memory first (`super.append`), then fires a **non-blocking, fire-and-forget Lettuce write-behind** mirror to Redis as a `doOnSuccess` side effect; mirror failures are swallowed (logged + counted), never propagated.
- **Never make a blocking Redis call on a reactor thread** — it deadlocks SBA. Everything uses `ReactiveStringRedisTemplate` with a 3s timeout.
- **Startup hydration**: `hydrate()` replays the persisted log back into memory via `super.append` (no re-mirroring), tolerating per-instance `OptimisticLockingException` (live discovery wins). It's triggered off `ApplicationReadyEvent` (async) so a slow/down Redis can't delay readiness. By default it **skips discovery-sourced instances** (registration `source="discovery"`): a `DiscoveryClient` re-registers the live ones and its `removeStaleInstances()` prunes the rest, so replaying them would only resurrect dead instances (e.g. old k8s pod IPs) as ghosts. Toggle with `hydrate-discovered`; disable the whole step with `hydrate-on-startup=false`.
- **Health, not liveness**: `EventStorePersistenceHealthIndicator` reports a **custom `DEGRADED` status** (not `DOWN`) when Redis is unreachable — HTTP 200, deliberately kept out of readiness/liveness groups so an outage is visible in `/actuator/health` and the SBA console without restarting the pod. This is the fix for the old blind spot where disabling the redis health check hid a silently-emptied registry.
- **Storage**: one Redis sorted set per instance `<prefix>:events:<instanceId>` scored by event version, member = Base64 of the JDK-serialized `InstanceEvent`; plus a set `<prefix>:instances`. Prefix default `sba:eventstore` (`RedisEventStoreProperties`). Each event key gets a **refreshed TTL** (`event-ttl`, default 24h) on every write, so an instance that stops being written self-evicts and the mirror doesn't accumulate stale ids; reads lazily `SREM` any set member whose key has already expired.
- **Security**: events are JDK-serialized (matching SBA's `HazelcastEventStore`). JDK deserialization of untrusted bytes is an RCE vector — the target Redis **must** be authenticated and network-restricted to the admin. Never point this at a shared/open Redis.
- **Activation** (`ReactiveRedisEventStoreAutoConfiguration`): `@ConditionalOnProperty(sba.eventstore.type=redis)` + `@ConditionalOnMissingBean(InstanceEventStore)`, ordered after `DataRedisReactiveAutoConfiguration` and before `AdminServerAutoConfiguration`. Registered in `META-INF/spring/…AutoConfiguration.imports`. Unset `sba.eventstore.type` → SBA keeps its default in-memory store, so the jar is safe to have on the classpath without turning it on.

## Consumer config (Redis store)

```yaml
spring:
  data:
    redis: { host: ..., port: ..., password: ... }   # standard Boot connection props
sba:
  eventstore:
    type: redis                       # opt-in; anything else = SBA default in-memory
    redis:
      key-prefix: sba:eventstore      # Redis key namespace
      timeout: 3s                     # per-command Redis timeout (mirror / hydrate / ping)
      event-ttl: 24h                  # TTL per instance key, refreshed on write; stale ids self-evict
                                      #   (0/negative = never expire). Stops ghost buildup for churny
                                      #   fleets (k8s pods re-registering under new ids, autoscaling).
      hydrate-on-startup: true        # replay the persisted log into memory on startup
      hydrate-discovered: false       # if false (default), startup hydration SKIPS discovery-sourced
                                      #   instances — a DiscoveryClient (k8s/Eureka/Consul/…) re-registers
                                      #   the live ones and prunes the rest, so replaying them would only
                                      #   resurrect dead instances as ghosts. Client self-registrations
                                      #   are always hydrated. Set true to warm the registry pre-discovery.
```

All keys are optional; the values shown are the defaults. Full descriptions ship in
`spring-configuration-metadata.json` (generated from `RedisEventStoreProperties`' `@param` javadoc via
`spring-boot-configuration-processor`), so IDEs autocomplete them.
