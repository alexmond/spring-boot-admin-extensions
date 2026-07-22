<!--
  Environments page: SBA's Applications view with an extra outer accordion level grouping apps
  by their environment tag (environment -> application -> node). Reuses SBA's globally-registered
  primitives (sba-panel, sba-status-badge, sba-status, sba-tags, sba-button, font-awesome-icon)
  so it renders like /applications; row markup mirrors SBA's internal views/applications
  components + ApplicationStatusHero (those aren't importable from an extension).

  Data: SBA.useApplicationStore(). Environment = instance.tags.environment (the server flattens
  info.tags.* into the instance `tags` map — there is no `info` on the frontend model).
-->
<template>
  <section>
    <!-- Subnav — matches the Applications view: stat pills + refresh + filter. -->
    <sba-sticky-subnav>
      <div class="container mx-auto flex">
        <!-- ApplicationStats: Applications + Instances counts (over all apps). -->
        <div class="hidden md:flex mr-1 gap-1">
          <sba-tag :label="t('applications.applications')" :value="appCount" />
          <sba-tag :label="t('applications.instances')" :value="instanceCount" />
        </div>
        <sba-button
          class="mr-1"
          :title="t('applications.actions.refresh_applications')"
          @click="refresh"
        >
          <font-awesome-icon icon="rotate-left" />
        </sba-button>
        <div class="flex-1">
          <sba-input
            v-model="q"
            :placeholder="t('term.filter')"
            name="filter"
            type="search"
          >
            <template #prepend>
              <font-awesome-icon icon="filter" />
            </template>
          </sba-input>
        </div>
      </div>
    </sba-sticky-subnav>

    <div class="container mx-auto py-6">
    <!-- Top status hero — mirrors ApplicationStatusHero over ALL instances. -->
    <sba-panel>
      <div class="flex flex-row items-center justify-center my-2">
        <template v-if="all.total > 0">
          <template v-if="all.allUp">
            <font-awesome-icon icon="check-circle" class="text-green-500 text-6xl pr-4" />
            <div class="text-center">
              <h1 class="font-bold text-2xl" v-text="t('applications.all_up')" />
              <p class="text-gray-400" v-text="lastUpdate" />
            </div>
          </template>
          <template v-else-if="all.allDown">
            <font-awesome-icon icon="minus-circle" class="text-red-500 text-6xl pr-4" />
            <div class="text-center">
              <h1 class="font-bold text-2xl" v-text="t('applications.all_down')" />
              <p class="text-gray-400" v-text="lastUpdate" />
            </div>
          </template>
          <template v-else-if="all.allUnknown">
            <font-awesome-icon icon="question-circle" class="text-gray-300 text-6xl pr-4" />
            <div class="text-center">
              <h1 class="font-bold text-2xl" v-text="t('applications.all_unknown')" />
              <p class="text-gray-400" v-text="lastUpdate" />
            </div>
          </template>
          <template v-else-if="all.someDown">
            <font-awesome-icon icon="minus-circle" class="text-red-500 text-6xl pr-4" />
            <div class="text-center">
              <h1 class="font-bold text-2xl" v-text="t('applications.some_down')" />
              <p class="text-gray-400" v-text="lastUpdate" />
            </div>
          </template>
          <template v-else-if="all.someUnknown">
            <font-awesome-icon icon="question-circle" class="text-gray-300 text-6xl pr-4" />
            <div class="text-center">
              <h1 class="font-bold text-2xl" v-text="t('applications.some_unknown')" />
              <p class="text-gray-400" v-text="lastUpdate" />
            </div>
          </template>
          <template v-else>
            <font-awesome-icon icon="check-circle" class="text-green-500 text-6xl pr-4" />
            <div class="text-center">
              <h1 class="font-bold text-2xl" v-text="t('applications.all_up')" />
              <p class="text-gray-400" v-text="lastUpdate" />
            </div>
          </template>
        </template>
        <template v-else>
          <font-awesome-icon icon="frown-open" class="text-gray-500 text-6xl pr-4" />
          <h1 class="font-bold text-2xl" v-text="t('applications.no_applications_registered')" />
        </template>
      </div>
    </sba-panel>

    <!-- LEVEL 1: environment -->
    <sba-panel
      v-for="env in environments"
      :key="env.name"
      class="application-group"
      @title-click="toggleEnv(env.name)"
    >
      <template #title>
        <div class="items-center inline-flex flex-row min-w-116">
          <font-awesome-icon
            icon="chevron-down"
            :class="{ '-rotate-90': !isEnvOpen(env.name), 'mr-2 transition-[transform]': true }"
          />
          <sba-status-badge class="mr-2" :status="env.status" />
          <span v-text="env.name" />
          <span
            class="ml-2 text-sm text-gray-500 self-end"
            v-text="t('term.instances_tc', { count: env.instanceCount })"
          />
        </div>
      </template>

      <template v-if="isEnvOpen(env.name)" #default>
        <!-- LEVEL 2: application (indented under the environment) -->
        <div class="pl-8 border-l-2 border-gray-100 ml-4">
          <sba-panel
            v-for="app in env.applications"
            :key="app.name"
            :seamless="true"
            @title-click="toggle(openApps, env.name + '/' + app.name)"
          >
            <template #title>
              <div class="items-center inline-flex flex-row min-w-116">
                <font-awesome-icon
                  icon="chevron-down"
                  :class="{ '-rotate-90': !isAppOpen(env.name + '/' + app.name), 'mr-2 transition-[transform]': true }"
                />
                <sba-status-badge class="mr-2" :status="app.status || statusOf(app.instances)" />
                <span v-text="app.name" />
                <span
                  class="ml-2 text-sm text-gray-500 self-end"
                  v-text="t('term.instances_tc', { count: app.instances.length })"
                />
              </div>
              <span class="hidden lg:inline ml-4" v-text="app.instances[0].buildVersion" />
            </template>

            <template v-if="isAppOpen(env.name + '/' + app.name)" #default>
              <!-- LEVEL 3: node (instance), indented under the application -->
              <ul class="pl-8 border-l-2 border-gray-100 ml-4">
                <li
                  v-for="instance in app.instances"
                  :key="instance.id"
                  class="flex p-2 pr-4 hover:bg-gray-100 gap-2 odd:bg-gray-50 items-center cursor-pointer"
                  @click="showDetails(instance)"
                >
                  <div class="pt-1 md:w-16 text-center">
                    <sba-status :date="instance.statusTimestamp" :status="instance.statusInfo.status" />
                  </div>
                  <div class="flex-1">
                    <section class="grid grid-cols-2 md:grid-cols-[26.5rem_1fr] items-center w-full">
                      <div class="flex" style="grid-area: 1 / 1 / 1 / 3">
                        <div
                          class="text-ellipsis overflow-hidden whitespace-nowrap"
                          v-text="instance.registration.serviceUrl || instance.registration.healthUrl"
                        />
                        <div class="ml-1 flex gap-1 items-start" @click.stop>
                          <sba-button
                            v-if="instance.registration.serviceUrl"
                            as="a"
                            :href="instance.registration.serviceUrl"
                            size="2xs"
                            target="_blank"
                          >
                            <font-awesome-icon icon="home" size="xs" />
                          </sba-button>
                          <sba-button
                            v-if="instance.registration.managementUrl"
                            as="a"
                            :href="instance.registration.managementUrl"
                            size="2xs"
                            target="_blank"
                          >
                            <font-awesome-icon icon="clipboard-list" size="xs" />
                          </sba-button>
                          <sba-button
                            v-if="instance.registration.healthUrl"
                            as="a"
                            :href="instance.registration.healthUrl"
                            size="2xs"
                            target="_blank"
                          >
                            <font-awesome-icon icon="heart" size="xs" />
                          </sba-button>
                        </div>
                      </div>
                      <span class="instance-id" v-text="instance.id" />
                      <span class="instance-version text-right lg:text-left" v-text="instance.buildVersion" />
                    </section>
                    <div
                      v-if="Object.keys(instance.tags ?? {}).length"
                      class="mt-2 hidden lg:block overflow-x-auto"
                    >
                      <sba-tags :small="true" :tags="instance.tags" />
                    </div>
                  </div>
                </li>
              </ul>
            </template>
          </sba-panel>
        </div>
      </template>
    </sba-panel>
    </div>
  </section>
</template>

<script>
/* global SBA */
import { computed, getCurrentInstance, reactive, ref } from "vue";

// SBA's status buckets (services/instance.ts) — copied since they aren't on the SBA global.
const UP_STATES = ["UP"];
const DOWN_STATES = ["OUT_OF_SERVICE", "DOWN", "OFFLINE", "RESTRICTED"];
const UNKNOWN_STATES = ["UNKNOWN"];

// getStatusInfo() from services/application.ts — over a set of instances.
const getStatusInfo = (instances) => {
  const total = instances.length;
  const up = instances.filter((i) => UP_STATES.includes(i.statusInfo.status)).length;
  const down = instances.filter((i) => DOWN_STATES.includes(i.statusInfo.status)).length;
  const unknown = instances.filter((i) => UNKNOWN_STATES.includes(i.statusInfo.status)).length;
  return {
    total,
    allUp: total > 0 && up === total,
    allDown: total > 0 && down === total,
    allUnknown: total > 0 && unknown === total,
    someDown: down > 0 && down < total,
    someUnknown: unknown > 0 && unknown < total,
  };
};

// Roll a set of instances up to one status for a group badge.
const statusOf = (instances) => {
  const s = instances.map((i) => i.statusInfo.status);
  if (s.some((x) => DOWN_STATES.includes(x))) return "DOWN";
  if (s.length && s.every((x) => UP_STATES.includes(x))) return "UP";
  if (s.some((x) => UNKNOWN_STATES.includes(x))) return "UNKNOWN";
  return s[0] || "UNKNOWN";
};

export default {
  setup() {
    const { applications } = SBA.useApplicationStore();
    const { t } = SBA.useI18n();
    // vue-router isn't externalized for the extension, but the SBA app registers it, so the
    // router is reachable via globalProperties ($router). Used to open an instance's detail page.
    const router = getCurrentInstance().appContext.config.globalProperties.$router;
    // Clicking an instance row → SBA's instance detail view (same target as the Applications list).
    const showDetails = (instance) =>
      router?.push({ name: "instances/details", params: { instanceId: instance.id } });
    const openEnvs = reactive(new Set());
    const openApps = reactive(new Set());
    const q = ref("");
    const lastUpdate = ref(new Date().toLocaleString());

    // An app's environment comes from its instances' info.tags.environment (the server flattens
    // info.tags.* onto each instance's `tags` map). Offline / just-registered / stale-discovery
    // ("ghost") instances haven't reported their /actuator/info yet, so they carry no tags — pick
    // the first instance that actually HAS an environment rather than instances[0]. Otherwise an
    // app whose first instance is an untagged ghost falls into "untagged" even though its live
    // instances are tagged (which is exactly what happened with builder-runner's stale IP entry).
    const envOf = (app) =>
      app.instances?.find((i) => i.tags?.environment)?.tags.environment || "untagged";
    const allApps = computed(() => applications.value ?? applications ?? []);
    const allInstances = computed(() => allApps.value.flatMap((a) => a.instances));
    const all = computed(() => getStatusInfo(allInstances.value)); // hero reflects ALL, not the filter

    // Subnav stats (match ApplicationStats — totals, unfiltered).
    const appCount = computed(() => allApps.value.length);
    const instanceCount = computed(() => allInstances.value.length);

    // Refresh = SBA's Application.refreshApplications() → POST applications (re-poll instances).
    // Resolve relative to document.baseURI so it honors any context path; SSE updates the store.
    const refresh = async () => {
      try {
        await fetch(new URL("applications", document.baseURI), { method: "POST" });
        lastUpdate.value = new Date().toLocaleString();
      } catch (e) {
        /* store keeps updating via SSE; ignore transient refresh errors */
      }
    };

    // Filter predicate — match query against env, app name, and instance id/url/tags.
    const matchApp = (app, envName) => {
      const query = q.value.trim().toLowerCase();
      if (!query) return true;
      if (envName.toLowerCase().includes(query)) return true;
      if (app.name.toLowerCase().includes(query)) return true;
      return app.instances.some(
        (i) =>
          String(i.id ?? "").toLowerCase().includes(query) ||
          String(i.registration?.serviceUrl ?? "").toLowerCase().includes(query) ||
          Object.values(i.tags ?? {}).some((v) => String(v).toLowerCase().includes(query))
      );
    };

    const environments = computed(() => {
      const apps = applications.value ?? applications ?? [];
      const groups = new Map();
      for (const app of apps) {
        const name = envOf(app);
        if (!matchApp(app, name)) continue;
        if (!groups.has(name)) groups.set(name, []);
        groups.get(name).push(app);
      }
      return [...groups.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, list]) => {
          const instances = list.flatMap((a) => a.instances);
          return { name, applications: list, instanceCount: instances.length, status: statusOf(instances) };
        });
    });

    // Filtering only narrows the list — it never changes expand state (matches the Applications
    // view). Environments and apps stay fully under the user's control: expand an env to reveal
    // its matching apps, then an app to drill into its nodes.
    const isEnvOpen = (name) => openEnvs.has(name);
    const isAppOpen = (key) => openApps.has(key);

    const toggle = (set, key) => (set.has(key) ? set.delete(key) : set.add(key));
    const toggleEnv = (name) => toggle(openEnvs, name);
    return {
      environments, all, lastUpdate, q, appCount, instanceCount, refresh, showDetails,
      openEnvs, openApps, toggle, toggleEnv, isEnvOpen, isAppOpen, statusOf, t,
    };
  },
};
</script>
