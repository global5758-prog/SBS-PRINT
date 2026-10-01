const CACHE = "sbprint-v3";
const ASSETS = [
  "/print/",
  "/print/index.html",
  "/print/manifest.json",
  "/print/icons/icon-192.png",
  "/print/icons/icon-512.png",
  "/print/icons/icon-maskable-192.png",
  "/print/icons/icon-maskable-512.png"
];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(ASSETS)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;

  // Halaman utama: ambil versi terbaru dari server dulu (supaya update langsung terasa),
  // pakai cache hanya kalau sedang offline.
  if (e.request.mode === "navigate" || url.pathname === "/print/" || url.pathname.endsWith("/index.html")) {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          if (res && res.status === 200) {
            const clone = res.clone();
            caches.open(CACHE).then((cache) => cache.put("/print/index.html", clone));
          }
          return res;
        })
        .catch(() => caches.match("/print/index.html").then((c) => c || caches.match("/print/")))
    );
    return;
  }

  // Aset lain (ikon, manifest): cache dulu, perbarui di belakang layar.
  e.respondWith(
    caches.match(e.request).then((cached) => {
      const fetchPromise = fetch(e.request)
        .then((res) => {
          if (res && res.status === 200) {
            const clone = res.clone();
            caches.open(CACHE).then((cache) => cache.put(e.request, clone));
          }
          return res;
        })
        .catch(() => cached);
      return cached || fetchPromise;
    })
  );
});
