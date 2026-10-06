# sba-extension-environments

A Spring Boot Admin UI extension that adds an **Environments** page: the standard Applications
view with one extra level on top, grouping applications by environment
(environment → application → instance).

## Install

Add it to your Spring Boot Admin **server**. There is nothing to configure; the page appears in
the top navigation.

```xml
<dependency>
    <groupId>org.alexmond</groupId>
    <artifactId>sba-extension-environments</artifactId>
    <version>4.1.1.1</version>
</dependency>
```

## Tell it which environment an instance belongs to

The page groups by the `environment` tag of each instance. Set it on the **client** application:

```yaml
management:
  info:
    env:
      enabled: true
info:
  tags:
    environment: prod
```

Grouping is per application: it is listed under the tag of its first tagged instance. An
application with no tagged instance is listed under `untagged`.

## How it works

The jar holds a built Vue bundle under
`META-INF/spring-boot-admin-server-ui/extensions/environments/`. Spring Boot Admin serves and
loads every extension it finds there. The bundle uses the Vue runtime and components the admin UI
already provides, so it must match the Spring Boot Admin line it was built for — see the version
table in the [project README](../README.md).
