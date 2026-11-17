"""Teste l'appli téléphone sur un émulateur Android : lancement, site chargé, extensions natives, écrans, erreurs."""
import json, os, re, subprocess, sys, time, urllib.request
import websocket

APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, 'journal.txt'), 'w')
PKG = 'app.runelift'
def log(*a): print(*a, flush=True); print(*a, file=LOG, flush=True)
def adb(*a, timeout=120):
    try: return subprocess.run(['adb'] + list(a), capture_output=True, timeout=timeout).stdout
    except Exception as e: log('adb error', a, e); return b''
def sh(c): return adb('shell', c).decode('utf-8', 'ignore')
n = [0]
def shot(name):
    n[0] += 1; open(os.path.join(OUT, f'{n[0]:02d}_{name}.png'), 'wb').write(adb('exec-out', 'screencap', '-p')); log('capture', name)

log('installation :', adb('install', '-r', '-g', APK, timeout=300).decode()[-200:].strip())
sh('logcat -c')
sh(f'am start -n {PKG}/.MainActivity')
time.sleep(30); shot('lancement')

# Accès à la page de l'appli (WebView) par les outils de développement
ws = None
for k in range(20):
    pid = sh(f'pidof {PKG}').strip()
    if pid:
        adb('forward', 'tcp:9222', f'localabstract:webview_devtools_remote_{pid}')
        try:
            pages = json.load(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=5))
            pg = [p for p in pages if p.get('type') == 'page' and 'webSocketDebuggerUrl' in p]
            if pg: ws = websocket.create_connection(pg[0]['webSocketDebuggerUrl'], timeout=60, suppress_origin=True); log('page :', pg[0].get('url')); break
        except Exception as e: log('devtools', e)
    time.sleep(3)
errs = []
mid = [0]
def cdp(method, params=None):
    mid[0] += 1; ws.send(json.dumps({'id': mid[0], 'method': method, 'params': params or {}}))
    while True:
        m = json.loads(ws.recv())
        if m.get('method') == 'Runtime.exceptionThrown': errs.append(m['params']['exceptionDetails'].get('exception', {}).get('description', m['params']['exceptionDetails'].get('text', ''))[:300])
        if m.get('method') == 'Runtime.consoleAPICalled' and m['params'].get('type') in ('error', 'warning'): errs.append(m['params'].get('type') + ': ' + ' '.join(str(a.get('value', a.get('description', a.get('preview', '')))) for a in m['params'].get('args', []))[:400])
        if m.get('id') == mid[0]: return m
def ev(expr, wait=True):
    r = cdp('Runtime.evaluate', {'expression': expr, 'awaitPromise': wait, 'returnByValue': True})
    res = r.get('result', {})
    if 'exceptionDetails' in res: return 'ERREUR: ' + str(res['exceptionDetails'].get('exception', {}).get('description', ''))[:300]
    return res.get('result', {}).get('value')

if not ws:
    log('IMPOSSIBLE de joindre la page de l\'appli')
else:
    cdp('Runtime.enable'); cdp('Page.enable')
    cdp('Page.reload'); t0 = time.time()
    while time.time() - t0 < 20:  # messages de la console au démarrage
        try:
            ws.settimeout(2); m = json.loads(ws.recv())
            if m.get('method') == 'Runtime.exceptionThrown': errs.append('démarrage: ' + str(m['params']['exceptionDetails'].get('exception', {}).get('description', m['params']['exceptionDetails'].get('text', '')))[:300])
            if m.get('method') == 'Runtime.consoleAPICalled' and m['params'].get('type') in ('error', 'warning'): errs.append('démarrage ' + m['params'].get('type') + ': ' + ' '.join(str(a.get('value', a.get('description', a.get('preview', '')))) for a in m['params'].get('args', []))[:400])
        except Exception: pass
    ws.settimeout(60)
    log('erreurs au démarrage :', errs or 'aucune'); errs.clear()
    log('version :', ev('APP_VERSION'), '| natif :', ev('isNative()'), '| Health Connect :', ev('!!HC()'), '| montre :', ev('!!WR()'))
    log('extensions :', ev('Object.keys(Capacitor.Plugins).join(", ")'))
    log('Health Connect status :', ev('Capacitor.Plugins.RLHealth.status().then(r=>JSON.stringify(r)).catch(e=>"err "+e.message)'))
    log('Montre status :', ev('Capacitor.Plugins.RLWear.status().then(r=>JSON.stringify(r)).catch(e=>"err "+e.message)'))
    # Accueil passé (pas de compte Google dans l'émulateur), puis tous les écrans
    ev("""(()=>{agLater=Date.now()+1e9; try{obClose()}catch(e){}; S.settings.onboarded=true; S.settings.name='Test'; S.settings.tour=Object.fromEntries(TOUR_LIST.map(x=>[x[0],1])); S.settings.tourPending=false;
      if(!S.routines.length){const sp=starterProgram('muscle',4,'int');S.routines=sp.rs;S.schedule=sp.sched;}
      save(); closeAll(); document.querySelectorAll('#ob,#tour,#authgate').forEach(x=>x.remove()); setRoot('train'); render(); return 'ok'})()""")
    time.sleep(2); shot('accueil')
    for v in ['train', 'stretch', 'cardio', 'timers', 'guild', 'profile', 'settings', 'stats', 'history', 'friends']:
        ev(f"(()=>{{closeAll();document.querySelectorAll('#tour,#authgate').forEach(x=>x.remove());stack=[{{v:'{v}'}}];render();window.scrollTo(0,0);return 1}})()")
        time.sleep(1.5); shot('ecran_' + v)
    ev("(()=>{stack=[{v:'settings'}];secOpen.add('health');secOpen.add('watch');render();const e=document.querySelector('[data-sec=health]');if(e)e.scrollIntoView();return 1})()"); time.sleep(1.5); shot('reglages_sante')
    # Une séance : commencer, cocher une série, repos, terminer
    log('séance :', ev("(()=>{startWorkout(S.routines[0].id);const s=S.active.exercises[0].sets[0];s.r=s.r||8;return S.active.routineName})()"))
    time.sleep(2); shot('seance')
    ev("(()=>{const b=document.querySelector('[data-act=check]');if(b)b.click();return 1})()"); time.sleep(2); shot('serie_cochee')
    ev("(()=>{window.confirm=()=>true;S.active.exercises.forEach(e=>e.sets.forEach(s=>{s.done=true;if(s.r===''||s.r==null)s.r=s.ph||8;if(s.w===''||s.w==null)s.w=50}));fakeAct('finish',{});return S.history.length})()"); time.sleep(3); shot('fin_seance')
    log('séances dans l\'historique :', ev('S.history.length'))
    log('erreurs dans la page :', errs if errs else 'aucune')

crash = sh('logcat -d -b crash'); fatal = [l for l in sh('logcat -d').splitlines() if 'FATAL EXCEPTION' in l or ('AndroidRuntime' in l and ' E ' in l)]
log('plantages :', (crash.strip()[:1500] or 'aucun'), fatal[:10])
log('toujours en vie :', bool(sh(f'pidof {PKG}').strip()))
cons = [l for l in sh('logcat -d -s Capacitor/Console:E chromium:E').splitlines() if l.strip()][:30]
log('console (erreurs) :', '\n'.join(cons) or 'aucune')
