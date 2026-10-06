# Changelog

Each Spring Boot line has its own entries. Versions are `<boot-version>.<revision>`.

## 4.1.1.1 (2026-10-06)

First release to Maven Central, on Spring Boot 4.1.1 / Spring Boot Admin 4.1.3.

- `sba-extension-environments` — an Environments page grouping applications by `info.tags.environment`.
- `sba-extension-live-metrics` — live, rolling time-series charts for any actuator metric.
- `sba-extension-store-redis` — a resilient Redis-backed `InstanceEventStore`.
- Environments: instance links are shown only for `http` / `https` registration URLs.
