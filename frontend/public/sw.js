// Network-first cache. A new deploy is served from the network; the previous
// cache is deleted on activate so an installed PWA does not keep stale assets.
// Backend paths (/api/, /doc/, /vod/) carry per-user, authenticated content:
// they always go straight to the network and are never stored or served from cache.
const CACHE = "astra-pwa-3";
const NETWORK_ONLY = ["/api/", "/doc/", "/vod/"];

function networkOnly(url) {
  if (url.pathname === "/api/catalog") return false;
  return NETWORK_ONLY.some((prefix) => url.pathname.startsWith(prefix));
}

self.addEventListener("install", (event) => {
  self.skipWaiting();
  event.waitUntil(caches.open(CACHE));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // Not handled here: the browser fetches it directly, with no cache fallback.
  if (networkOnly(url)) return;
  event.respondWith((async () => {
    try {
      const fresh = await fetch(request);
      if (fresh && fresh.ok) {
        const cache = await caches.open(CACHE);
        cache.put(request, fresh.clone());
      }
      return fresh;
    } catch (error) {
      const cached = await caches.match(request);
      if (cached) return cached;
      if (request.mode === "navigate") {
        const home = await caches.match("/index.html");
        if (home) return home;
      }
      throw error;
    }
  })());
});
