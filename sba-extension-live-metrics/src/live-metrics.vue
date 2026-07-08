<!--
  Live Metrics — a per-instance SBA view (instance left sidebar → Insights → below Metrics).
  It's the standard Metrics view for THIS instance, but every selected metric is drawn as a live,
  rolling line chart (like the CPU/Memory graphs on the Overview) instead of a one-shot value.

  Flow (mirrors the Metrics view's add/remove UX): pick metric -> (optional) narrow by tag ->
  "Add graph". Each graph polls the metric on a shared interval via the instance's own actuator
  proxy (`instance.fetchMetric`, so it honours auth/base URL), keeps a rolling buffer, and renders
  an auto-scaled SVG sparkline + current value. Graphs are removable and persist per-instance across
  reloads via localStorage. The view is only registered when the instance exposes `metrics`.
-->
<template>
  <section>
    <!-- Subnav — the "add a graph" form: metric (+ tags) + interval + Add. -->
    <sba-sticky-subnav>
      <div class="container mx-auto flex flex-wrap items-center gap-2 py-1">
        <label class="flex items-center gap-1 text-sm">
          <span class="text-gray-500" v-text="t('live-metrics.metric')" />
          <select
            v-model="selMetric"
            class="border border-gray-300 rounded px-2 py-1 bg-white disabled:opacity-50"
            style="min-width:14rem;max-width:22rem"
            :disabled="!metricNames.length"
            @change="onMetricChange"
          >
            <option v-if="!metricNames.length" value="">{{ metricsLoading ? t('live-metrics.loading') : '—' }}</option>
            <option v-for="n in metricNames" :key="n" :value="n" v-text="n" />
          </select>
        </label>

        <!-- Optional tag filters for the chosen metric (drill down like the Metrics view). -->
        <label v-for="at in availTags" :key="at.tag" class="flex items-center gap-1 text-sm">
          <span class="text-gray-400" v-text="at.tag" />
          <select v-model="selTags[at.tag]" class="border border-gray-300 rounded px-2 py-1 bg-white" style="max-width:16rem">
            <option value="">{{ t('live-metrics.tag_all') }}</option>
            <option v-for="v in at.values" :key="v" :value="v" v-text="v" />
          </select>
        </label>

        <sba-button :disabled="!canAdd" @click="addGraph">
          <span class="mr-1 font-bold">+</span>
          <span v-text="t('live-metrics.add')" />
        </sba-button>

        <div class="flex-1" />

        <label class="flex items-center gap-1 text-sm">
          <span class="text-gray-500" v-text="t('live-metrics.every')" />
          <select v-model.number="intervalMs" class="border border-gray-300 rounded px-2 py-1 bg-white" @change="restartPolling">
            <option :value="2000">2s</option>
            <option :value="5000">5s</option>
            <option :value="10000">10s</option>
            <option :value="30000">30s</option>
          </select>
        </label>
      </div>
    </sba-sticky-subnav>

    <div class="container mx-auto py-6">
      <!-- Empty state -->
      <sba-panel v-if="!graphs.length">
        <div class="flex flex-col items-center justify-center text-center py-10">
          <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" class="mb-4 text-gray-300" style="width:4rem;height:4rem" fill="currentColor" viewBox="0 0 512 512">
            <path d="M64 64c0-17.7-14.3-32-32-32S0 46.3 0 64L0 400c0 44.2 35.8 80 80 80l400 0c17.7 0 32-14.3 32-32s-14.3-32-32-32L80 416c-8.8 0-16-7.2-16-16L64 64zm406.6 86.6c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L320 210.7l-57.4-57.4c-12.5-12.5-32.8-12.5-45.3 0l-112 112c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0L240 269.3l57.4 57.4c12.5 12.5 32.8 12.5 45.3 0l128-128z" />
          </svg>
          <h1 class="font-bold text-2xl" v-text="t('live-metrics.empty_title')" />
          <p class="text-gray-400 mt-1" v-text="t('live-metrics.empty_hint')" />
        </div>
      </sba-panel>

      <!-- Responsive grid of live charts. -->
      <div v-else class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <sba-panel v-for="g in graphs" :key="g.id">
          <div class="flex flex-col">
            <!-- header: title + remove -->
            <div class="flex items-start justify-between gap-2">
              <div class="min-w-0">
                <div class="font-semibold text-ellipsis overflow-hidden whitespace-nowrap" v-text="g.metric" />
                <div class="text-xs text-gray-500 flex flex-wrap gap-x-2">
                  <span v-for="(v, k) in g.tags" :key="k" v-text="`${k}:${v}`" />
                  <span v-if="g.statistic" class="text-gray-400" v-text="g.statistic" />
                </div>
              </div>
              <div class="flex items-baseline gap-2 shrink-0">
                <div class="text-right">
                  <div class="text-xl font-bold tabular-nums" :class="g.error ? 'text-red-500' : ''"
                       v-text="g.error ? t('live-metrics.err') : formatValue(g.last, g.unit)" />
                  <div v-if="g.unit && !g.error" class="text-xs text-gray-400" v-text="g.unit" />
                </div>
                <sba-button size="xs" :title="t('live-metrics.remove')" @click="removeGraph(g.id)">
                  <span class="font-bold leading-none">&times;</span>
                </sba-button>
              </div>
            </div>

            <!-- chart -->
            <div class="mt-3 relative" style="height: 8rem">
              <svg class="w-full h-full" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
                <defs>
                  <linearGradient :id="'lmfill-' + g.id" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" :stop-color="g.error ? '#ef4444' : '#3b82f6'" stop-opacity="0.28" />
                    <stop offset="100%" :stop-color="g.error ? '#ef4444' : '#3b82f6'" stop-opacity="0" />
                  </linearGradient>
                </defs>
                <template v-if="pathOf(g).ok">
                  <path :d="pathOf(g).area" :fill="'url(#lmfill-' + g.id + ')'" />
                  <path :d="pathOf(g).line" fill="none" :stroke="g.error ? '#ef4444' : '#3b82f6'" stroke-width="1.5"
                        vector-effect="non-scaling-stroke" />
                </template>
              </svg>
              <div v-if="!pathOf(g).ok" class="absolute inset-0 flex items-center justify-center text-gray-300 text-sm">
                <span v-text="g.error ? g.error : t('live-metrics.collecting')" />
              </div>
            </div>

            <!-- footer: min / max over the window -->
            <div v-if="pathOf(g).ok" class="mt-1 flex justify-between text-xs text-gray-400 tabular-nums">
              <span v-text="'min ' + formatValue(pathOf(g).min, g.unit)" />
              <span v-text="t('live-metrics.points', { n: g.points.length })" />
              <span v-text="'max ' + formatValue(pathOf(g).max, g.unit)" />
            </div>
          </div>
        </sba-panel>
      </div>
    </div>
  </section>
</template>

<script>
/* global SBA */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";

const CAP = 60; // rolling window length (points kept per graph)
const STORE_PREFIX = "sba.live-metrics.graphs.v1."; // + instanceId

// Humanize a measurement value for the current-value readout + axis labels.
const formatValue = (v, unit) => {
  if (v == null || Number.isNaN(v)) return "—";
  if (unit === "bytes") {
    const neg = v < 0 ? "-" : "";
    let n = Math.abs(v);
    const u = ["B", "KB", "MB", "GB", "TB", "PB"];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) {
      n /= 1024;
      i++;
    }
    return neg + (i === 0 ? n : n.toFixed(1)) + " " + u[i];
  }
  const abs = Math.abs(v);
  if (abs !== 0 && abs < 0.01) return v.toExponential(2);
  if (abs < 1) return v.toFixed(4).replace(/0+$/, "").replace(/\.$/, "");
  if (abs < 1000) return Number(v.toFixed(3)).toLocaleString();
  return Math.round(v).toLocaleString();
};

export default {
  // SBA passes the current instance to every instance view as a prop.
  props: {
    instance: {
      type: Object,
      required: true,
    },
  },
  setup(props) {
    const { t } = SBA.useI18n();
    const instance = props.instance;
    const storeKey = STORE_PREFIX + instance.id;

    // --- add-a-graph form state ---
    const selMetric = ref("");
    const selTags = reactive({});
    const metricNames = ref([]);
    const availTags = ref([]);
    const metricsLoading = ref(false);
    const intervalMs = ref(5000);

    const canAdd = computed(() => !!selMetric.value);

    const loadMetricNames = async () => {
      metricsLoading.value = true;
      try {
        const res = await instance.fetchMetrics();
        metricNames.value = (res?.data?.names || []).slice().sort();
        const pref = ["jvm.memory.used", "process.cpu.usage", "system.cpu.usage"];
        selMetric.value =
          pref.find((p) => metricNames.value.includes(p)) || metricNames.value[0] || "";
        if (selMetric.value) await loadMetricMeta(selMetric.value);
      } catch (e) {
        metricNames.value = [];
      } finally {
        metricsLoading.value = false;
      }
    };

    const loadMetricMeta = async (metric) => {
      availTags.value = [];
      Object.keys(selTags).forEach((k) => delete selTags[k]);
      if (!metric) return;
      try {
        const res = await instance.fetchMetric(metric, {});
        availTags.value = res?.data?.availableTags || [];
        availTags.value.forEach((at) => (selTags[at.tag] = ""));
      } catch (e) {
        availTags.value = [];
      }
    };

    const onMetricChange = () => loadMetricMeta(selMetric.value);

    // --- graphs ---
    const graphs = reactive([]);
    let seq = 0;
    const newId = () => `g${Date.now().toString(36)}${(seq++).toString(36)}`;

    const persist = () => {
      try {
        const dump = graphs.map((g) => ({ metric: g.metric, tags: g.tags }));
        localStorage.setItem(storeKey, JSON.stringify(dump));
      } catch (e) {
        /* storage may be unavailable; graphs still work for the session */
      }
    };

    const makeGraph = (cfg) => ({
      id: newId(),
      metric: cfg.metric,
      tags: { ...(cfg.tags || {}) },
      unit: "",
      statistic: "",
      points: reactive([]),
      last: null,
      error: null,
      _inflight: false,
    });

    const addGraph = () => {
      if (!canAdd.value) return;
      const tags = {};
      Object.entries(selTags).forEach(([k, v]) => {
        if (v) tags[k] = v;
      });
      const g = makeGraph({ metric: selMetric.value, tags });
      graphs.push(g);
      persist();
      pollGraph(g); // immediate first sample
    };

    const removeGraph = (id) => {
      const idx = graphs.findIndex((g) => g.id === id);
      if (idx >= 0) graphs.splice(idx, 1);
      persist();
    };

    const pollGraph = async (g) => {
      if (g._inflight) return;
      g._inflight = true;
      try {
        const res = await instance.fetchMetric(g.metric, g.tags);
        const data = res && res.data;
        const m = data && data.measurements && data.measurements[0];
        const v = m ? m.value : null;
        if (data) g.unit = data.baseUnit || g.unit || "";
        if (m) g.statistic = m.statistic;
        if (v != null && !Number.isNaN(v)) {
          g.points.push(v);
          while (g.points.length > CAP) g.points.shift();
          g.last = v;
          g.error = null;
        } else if (!res) {
          g.error = "unavailable";
        }
      } catch (e) {
        g.error = (e && e.message) || "error";
      } finally {
        g._inflight = false;
      }
    };

    const tick = () => graphs.forEach(pollGraph);

    let timer = null;
    const startPolling = () => {
      if (timer) clearInterval(timer);
      timer = setInterval(tick, intervalMs.value);
    };
    const restartPolling = () => startPolling();

    // Compute the SVG line + area path (viewBox 0..100) from a graph's rolling buffer.
    const pathOf = (g) => {
      const pts = g.points;
      if (!pts || pts.length < 2) return { ok: false, min: g.last, max: g.last };
      let min = Infinity;
      let max = -Infinity;
      for (const v of pts) {
        if (v < min) min = v;
        if (v > max) max = v;
      }
      const span = max - min || Math.abs(max) || 1; // avoid divide-by-zero on flat series
      const n = pts.length;
      const PAD = 8; // vertical padding inside the 0..100 box
      const x = (i) => (i / (n - 1)) * 100;
      const y = (v) => 100 - PAD - ((v - min) / span) * (100 - 2 * PAD);
      let line = "";
      for (let i = 0; i < n; i++) {
        line += (i === 0 ? "M" : "L") + x(i).toFixed(2) + "," + y(pts[i]).toFixed(2) + " ";
      }
      const area = `M0,100 L${line.slice(1).trim()} L100,100 Z`;
      return { ok: true, line: line.trim(), area, min, max };
    };

    // --- lifecycle ---
    onMounted(async () => {
      await loadMetricNames(); // populates instance.availableMetrics so fetchMetric works
      try {
        const saved = JSON.parse(localStorage.getItem(storeKey) || "[]");
        saved.forEach((cfg) => graphs.push(makeGraph(cfg)));
      } catch (e) {
        /* ignore malformed storage */
      }
      startPolling();
      tick(); // paint first samples fast
    });

    onBeforeUnmount(() => {
      if (timer) clearInterval(timer);
    });

    return {
      t,
      selMetric,
      selTags,
      metricNames,
      availTags,
      metricsLoading,
      intervalMs,
      canAdd,
      graphs,
      onMetricChange,
      addGraph,
      removeGraph,
      restartPolling,
      pathOf,
      formatValue,
    };
  },
};
</script>
