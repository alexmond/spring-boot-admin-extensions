<!-- Nav label for the top-level Environments view (SBA renders the view's `handle` in the sidebar).
     Mirrors SBA's own views/applications/handle.vue exactly: when any instance is not UP it shows an
     exclamation-triangle plus a rounded danger count badge, so the Environments nav entry flags
     problems the same way the built-in Applications entry does. (Favicon swapping is left to SBA's
     own applications handle — it already manages the tab icon globally.) -->
<template>
  <span>
    <span v-if="downCount > 0" class="mr-2">
      <font-awesome-icon icon="exclamation-triangle" />
    </span>
    <span
      :class="{ 'has-badge has-badge-rounded has-badge-danger': downCount > 0 }"
      :data-badge="downCount > 0 ? downCount : undefined"
      v-text="t('environments.label')"
    />
  </span>
</template>

<script setup>
/* global SBA */
import { computed } from "vue";

const { t } = SBA.useI18n();
const { applications } = SBA.useApplicationStore();

// Count instances whose status is not UP, across all apps — the identical rule SBA's
// applications/handle.vue uses, so the badge/warning matches the Applications nav entry exactly.
// (A discovery-deregistered instance is gone from the store, so it isn't counted here either —
// same blind spot as, and consistent with, the built-in Applications entry.)
const downCount = computed(() => {
  const apps = applications.value ?? applications ?? [];
  return apps.reduce(
    (sum, app) =>
      sum + app.instances.filter((i) => i.statusInfo.status !== "UP").length,
    0
  );
});
</script>
