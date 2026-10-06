# Security policy

## Reporting a vulnerability

Please report security issues privately through
[GitHub security advisories](https://github.com/alexmond/spring-boot-admin-extensions/security/advisories/new), not in a public issue.
You should get a first answer within a week.

## Supported versions

Each Spring Boot line is a branch. Fixes go to the lines listed in the README version table.

## What to know before you deploy

### `sba-extension-store-redis` trusts its Redis

Events are stored with **JDK serialization**, the same way Spring Boot Admin's own Hazelcast store
does it. Reading them back deserializes bytes from Redis. Anyone who can write to that Redis can
make the admin server deserialize arbitrary objects, which is a remote-code-execution path.

- Point the store only at a Redis that **requires authentication** and is reachable only by the
  admin server.
- Never share that Redis, or its key prefix, with less trusted applications.
- Use TLS if the Redis is not on the same host or a private network.

### The UI extensions run inside the admin UI

`sba-extension-environments` and `sba-extension-live-metrics` are JavaScript bundles that Spring
Boot Admin loads into its own page. They run with the same privileges as the admin UI, so secure
the admin server itself (authentication, and who may register instances).

The extensions render instance data as text only and link only `http` / `https` registration URLs.

### Spring Boot Admin's own advisories

These extensions do not bundle Spring Boot Admin; you choose its version. Follow its advisories at
<https://github.com/codecentric/spring-boot-admin/security/advisories>. In particular,
GHSA-4jg4-pqcq-xf3x (stored XSS in Spring Boot Admin's health-details view) is fixed in Spring Boot
Admin `4.1.3` and `3.5.11`; there is no fixed `4.0.x` release.
