const CACHE = 'carnet-muscu-v4';
const FILES = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES))); self.skipWaiting(); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE && k !== 'carnet-remind').map(k => caches.delete(k)))));
  self.clients.claim();
});
// Réseau d'abord (pour recevoir les mises à jour), cache si hors ligne
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  // Google (connexion et sauvegarde Drive) : jamais en cache, ce sont des données personnelles
  if (/(^|\.)google(apis)?\.com$/.test(new URL(e.request.url).hostname)) return;
  e.respondWith(fetch(e.request).then(r => {
    const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request).then(r => r || caches.match('./index.html'))));
});
// Toucher une notification (cell ou montre) ramène à l'appli, sur la séance
self.addEventListener('notificationclick', e => {
  e.notification.close();
  if (e.notification.tag === 'carnet-remind') {
    e.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(cs => { const c = cs.find(x => 'focus' in x); return c ? c.focus() : self.clients.openWindow('./?go=quest'); }));
    return;
  }
  e.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(cs => {
    const c = cs.find(x => 'focus' in x);
    if (c) { c.postMessage({type: 'open-workout'}); return c.focus(); }
    return self.clients.openWindow('./?go=workout');
  }));
});
// Fin du repos : la page confie l'heure de fin au service worker, qui envoie la notification à l'heure
// même si Chrome a mis la page en pause (écran éteint). Chrome garde le service worker en vie jusqu'à ~5 min.
let restJob = null, lastAt = 0;
const restShow = d => { if (d.at === lastAt) return Promise.resolve(); lastAt = d.at; return self.registration.getNotifications({tag: 'carnet-rest'}).then(ns => {
  if (ns.some(n => n.data && n.data.at === d.at && n.data.fired)) return; // déjà envoyée pour ce repos
  return self.registration.showNotification(d.title, {body: d.body, tag: 'carnet-rest', renotify: true, icon: 'icon-192.png', badge: 'icon-192.png',
    vibrate: [300, 120, 300], timestamp: d.at, requireInteraction: false, data: {go: 'workout', at: d.at, fired: true}});
}); };
self.addEventListener('message', e => {
  const d = e.data || {};
  if (d.type === 'rest-at' || d.type === 'rest-cancel') {
    if (restJob) { clearTimeout(restJob.t); restJob.done(); restJob = null; }
    if (d.type === 'rest-cancel') return;
    const wait = Math.max(0, d.at - Date.now());
    e.waitUntil(new Promise(done => {
      const job = {done};
      job.t = setTimeout(() => { if (restJob === job) restJob = null; restShow(d).finally(done); }, wait);
      restJob = job;
    }));
  } else if (d.type === 'rest-fire') {
    if (restJob) { clearTimeout(restJob.t); restJob.done(); restJob = null; }
    e.waitUntil(restShow(d));
  }
});

// Rappels d'entraînement : la page laisse son plan dans le cache, Chrome réveille le service worker environ une fois par jour.
const ymd = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
async function remindCheck() {
  const c = await caches.open('carnet-remind'), r = await c.match('./__remind.json'); if (!r) return;
  const p = await r.json(); if (!p.on) return;
  const now = new Date(), dk = ymd(now);
  const sr = await c.match('./__remind-shown'); if (sr && (await sr.text()) === dk) return;
  if (now.getHours() < p.hour || now.getHours() >= 23) return;
  if (p.last && ymd(new Date(p.last)) === dk) return;
  let title = null;
  if (p.plan && p.days && p.days[dk]) title = p.tPlan.replace('#', p.days[dk]);
  else if (p.last) { const gap = Math.floor((now - new Date(p.last)) / 864e5); if (gap >= p.gap) title = p.tGap.replace('#', gap); }
  if (!title) return;
  await self.registration.showNotification(title, {body: p.body, tag: 'carnet-remind', icon: 'icon-192.png', badge: 'icon-192.png', data: {go: 'quest'}});
  await c.put('./__remind-shown', new Response(dk));
}
self.addEventListener('periodicsync', e => { if (e.tag === 'carnet-remind') e.waitUntil(remindCheck()); });
