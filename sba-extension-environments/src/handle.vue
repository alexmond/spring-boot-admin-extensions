<!-- Nav label for the top-level Environments view (SBA renders the view's `handle`). -->
<template>
  <span v-text="t('environments.label')" />
</template>

<script setup>
/* global SBA */
const { t } = SBA.useI18n();
</script>
