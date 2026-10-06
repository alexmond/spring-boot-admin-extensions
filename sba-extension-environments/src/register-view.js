/* global SBA */
// Registers an SBA view, working around SBA 4.1.3's broken extension API.
//
// In SBA 4.1.3 ViewRegistry.addView became async, but the useViewRegistry().addView wrapper handed to
// extensions still reads `[0].path` off its result (now a Promise) → "TypeError: Cannot read properties
// of undefined (reading 'startsWith')" AFTER the view is stored but BEFORE its route is added, so the
// view is in the nav yet unroutable. codecentric/spring-boot-admin#5724, fixed by PR #5725.
//
// On SBA versions without the bug addView succeeds and the fallback never runs. On 4.1.3 the fallback
// adds the route the same way SBA's (fixed) wrapper does. Delete this file once the minimum supported
// SBA carries the fix. (Kept as an identical copy in each UI module — they ship independently.)
export function addView(viewRegistry, config) {
  try {
    viewRegistry.addView(config);
  } catch (e) {
    if (!(e instanceof TypeError)) throw e;
    addRouteFallback(config.name);
  }
}

function addRouteFallback(name) {
  // SBA doesn't expose its router to extensions; reach it through the mounted Vue app (extension
  // scripts are injected after app.mount, so it's there).
  const router = document.querySelector("#app")?.__vue_app__?.config.globalProperties.$router;
  const view = SBA.viewRegistry.views.find((v) => v.name === name);
  if (!router || !view || router.hasRoute(view.name)) return;

  const route = { path: view.path, name: view.name, component: view.component, props: view.props, meta: { view } };
  if (view.parent && router.hasRoute(view.parent)) {
    router.addRoute(view.parent, route);
  } else {
    const path = (view.parent ? `${view.parent}/${view.path}` : view.path).replace(/\/+/g, "/");
    router.addRoute({ ...route, path: path.startsWith("/") ? path : `/${path}` });
  }

  // A deep link to this view resolved before the route existed — re-resolve it (SBA does the same on
  // its custom-routes-added event, which the broken wrapper never emits).
  if (!router.currentRoute.value.matched.length) router.replace(router.currentRoute.value.fullPath);
}
