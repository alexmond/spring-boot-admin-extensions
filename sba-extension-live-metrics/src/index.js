/* global SBA */
// Registers a per-instance "Live Metrics" view in the Spring Boot Admin UI. It sits in an
// instance's left sidebar under the Insights group, right below the built-in Metrics view
// (Metrics is order 50 → this is order 51). Same availability rule as Metrics: only shown when
// the instance exposes the actuator `metrics` endpoint. It replicates the Metrics view but draws
// each selected metric as a live, rolling line chart (like the CPU/Memory graphs). See
// live-metrics.vue.
import liveMetrics from "./live-metrics.vue";

SBA.use({
  install({ viewRegistry, i18n }) {
    viewRegistry.addView({
      name: "instances/live-metrics",
      parent: "instances",
      path: "live-metrics",
      component: liveMetrics,
      label: "instances.live-metrics.label",
      group: "insights", // VIEW_GROUP.INSIGHTS — same group as Metrics/Environment
      order: 51, // Metrics is 50 → appears directly below it
      isEnabled: ({ instance }) => instance.hasEndpoint("metrics"),
    });
    i18n.mergeLocaleMessage("en", {
      instances: { "live-metrics": { label: "Live Metrics" } },
      "live-metrics": {
        metric: "Metric",
        loading: "loading…",
        tag_all: "(all)",
        add: "Add graph",
        every: "Refresh",
        remove: "Remove graph",
        err: "error",
        collecting: "collecting…",
        points: "{n} pts",
        empty_title: "No graphs yet",
        empty_hint: "Pick a metric above, then Add graph.",
      },
    });
  },
});
