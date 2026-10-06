# sba-extension-live-metrics

A Spring Boot Admin UI extension that adds a **Live Metrics** view to every instance: pick any
actuator metric and watch it as a live, rolling line chart, like the CPU and memory graphs on the
instance overview.

## Install

Add it to your Spring Boot Admin **server**. There is nothing to configure.

```xml
<dependency>
    <groupId>org.alexmond</groupId>
    <artifactId>sba-extension-live-metrics</artifactId>
    <version>4.0.8.1</version>
</dependency>
```

The view appears under *Insights* for every instance that exposes the `metrics` actuator endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

## Use

1. Open an instance and choose **Live Metrics**.
2. Pick a metric, and optionally narrow it by tag.
3. Choose **Add graph**. Add as many as you like; each can be removed.

Graphs poll the instance on the chosen interval (2 s to 30 s) and are remembered in the browser's
local storage, so they are still there after a reload.

## How it works

The jar holds a built Vue bundle under
`META-INF/spring-boot-admin-server-ui/extensions/live-metrics/`. Spring Boot Admin serves and
loads every extension it finds there. The bundle uses the Vue runtime and components the admin UI
already provides, so it must match the Spring Boot Admin line it was built for — see the version
table in the [project README](../README.md).
