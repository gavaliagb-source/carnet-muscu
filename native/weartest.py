"""Teste l'appli montre sur un émulateur Wear OS : captures d'écran, plantages, fluidité."""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, 'journal.txt'), 'w')
PKG = 'app.runelift'

def log(*a):
    print(*a, flush=True); print(*a, file=LOG, flush=True)

def adb(*a, timeout=60):
    try:
        r = subprocess.run(['adb'] + list(a), capture_output=True, timeout=timeout)
        return r.stdout
    except Exception as e:
        log('adb error', a, e); return b''

def sh(cmd): return adb('shell', cmd).decode('utf-8', 'ignore')

n = [0]
def shot(name):
    n[0] += 1
    open(os.path.join(OUT, f'{n[0]:02d}_{name}.png'), 'wb').write(adb('exec-out', 'screencap', '-p'))
    log('capture', name)

def nodes():
    sh('rm -f /sdcard/u.xml')
    out = sh('uiautomator dump /sdcard/u.xml')
    x = sh('cat /sdcard/u.xml')
    if '<hierarchy' not in x:
        log('dump impossible :', out.strip()[:120]); return []
    try:
        root = ET.fromstring(x[x.index('<hierarchy'):])
    except Exception as e:
        log('xml', e); return []
    res = []
    for nd in root.iter('node'):
        b = list(map(int, re.findall(r'\d+', nd.get('bounds', '[0,0][0,0]'))))
        res.append((nd.get('text') or '', (b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return res

def tap_text(t, wait=2.5):
    # Appuie sur le bouton par son texte (crochet de test de l'appli en mode démo).
    sh(f"am start -n {PKG}/app.runelift.wear.WatchActivity --es rl_tap '{t}'")
    log('toucher', repr(t)); time.sleep(wait); wait_app(20); return True

def wait_app(maxs=60):
    for i in range(maxs):
        if 'WatchActivity' in sh('dumpsys window | grep -E "mCurrentFocus|mFocusedApp"'):
            time.sleep(2); log('appli prête après', i, 's'); return True
        time.sleep(1)
    log('appli pas prête'); return False

def swipe(x1, y1, x2, y2, ms=150, wait=1.2):
    adb('shell', 'input', 'swipe', *map(lambda v: str(int(v)), (x1, y1, x2, y2, ms))); time.sleep(wait)

m = re.search(r'(\d+)x(\d+)', sh('wm size')); W, H = (int(m[1]), int(m[2])) if m else (454, 454)
log('écran', W, H, sh('wm density').strip())

log(adb('install', '-r', '-g', APK, timeout=240).decode('utf-8', 'ignore'))
for p in ['android.permission.BODY_SENSORS', 'android.permission.POST_NOTIFICATIONS']:
    sh(f'pm grant {PKG} {p}')
sh('logcat -c')

sh('settings put secure user_setup_complete 1'); sh('settings put global device_provisioned 1')
sh('input keyevent KEYCODE_WAKEUP'); sh('svc power stayon true')

def launch(demo=True):
    sh(f'am force-stop {PKG}')
    sh(f'am start -n {PKG}/app.runelift.wear.WatchActivity' + (' --ez rl_demo true' if demo else ''))
    time.sleep(3); wait_app()

launch(); time.sleep(25)
shot('routines')
swipe(W / 2, H * 0.75, W / 2, H * 0.3, 300); shot('routines_bas'); swipe(W / 2, H * 0.3, W / 2, H * 0.8, 300)
tap_text('Épaule bras'); shot('detail_routine')
swipe(W / 2, H * 0.75, W / 2, H * 0.3, 300); shot('detail_bas'); swipe(W / 2, H * 0.3, W / 2, H * 0.8, 300)
sh(f'dumpsys gfxinfo {PKG} reset')
tap_text('Commencer', 4); shot('serie')
# roue de la charge : petit glissement puis coup de doigt fort
tap_text('✓', 2); shot('repos')
tap_text('+30', 1); shot('repos_plus30')
tap_text('Passer', 1.5); shot('serie_2')
wx, rx, cy = W * 0.346, W * 0.645, H * 0.476
swipe(wx, cy + 20, wx, cy - 22, 350, 2); shot('roue_charge_lent')
swipe(wx, cy + 45, wx, cy - 70, 45, 3); shot('roue_charge_coup_fort')
swipe(rx, cy - 20, rx, cy + 22, 350, 2); shot('roue_reps_baisse')
r = sh('input rotaryencoder scroll --axis SCROLL -1'); log('lunette :', r.strip()[:100]); time.sleep(1.5); shot('lunette')
tap_text('Série suivante', 1.5); shot('fleche_suivante')
tap_text('Série précédente', 1.5); shot('fleche_precedente')
swipe(W * 0.12, H / 2, W * 0.88, H / 2, 120, 1.5); shot('liste_exos')
swipe(W / 2, H * 0.8, W / 2, H * 0.25, 300); swipe(W / 2, H * 0.8, W / 2, H * 0.25, 300); shot('liste_exos_bas')
swipe(W * 0.88, H / 2, W * 0.12, H / 2, 120, 1.5)
swipe(W * 0.88, H / 2, W * 0.12, H / 2, 120, 1.5); shot('options')
tap_text('Remplacer', 2); shot('remplacer')
swipe(W / 2, H * 0.8, W / 2, H * 0.25, 300); shot('remplacer_bas')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1.5); shot('retour_options')
tap_text('Type', 1); shot('type_change')
tap_text('Ajouter une série', 1)
tap_text('Abandonner', 1.5); shot('confirmer_abandon')
tap_text('Non', 1.5); shot('apres_non')
gfx = sh(f'dumpsys gfxinfo {PKG}')
open(os.path.join(OUT, 'fluidite.txt'), 'w').write(gfx)
for line in gfx.splitlines():
    if re.search(r'Total frames|Janky frames|percentile|Slow|Frame deadline', line): log('fluidité', line.strip())
# même écran avec le texte de la montre en grand
sh('settings put system font_scale 1.3'); time.sleep(5)
launch(False)
for k in range(3):
    if 'WatchActivity' in sh('dumpsys window | grep -E "mCurrentFocus"'): break
    sh(f'am start -n {PKG}/app.runelift.wear.WatchActivity'); time.sleep(4)
time.sleep(4); shot('serie_texte_grand')
swipe(W * 0.12, H / 2, W * 0.88, H / 2, 120, 1.5); shot('liste_texte_grand')
swipe(W * 0.88, H / 2, W * 0.12, H / 2, 120, 1.5)
sh('settings put system font_scale 1.0'); time.sleep(4)
sh('am start -n app.runelift/app.runelift.wear.WatchActivity --ez rl_tapmode true')
time.sleep(4); shot('reprise_apres_relance')
crash = adb('logcat', '-d', '-b', 'crash').decode('utf-8', 'ignore')
open(os.path.join(OUT, 'plantages.txt'), 'w').write(crash or '(aucun)')
log('PLANTAGES :', 'aucun' if not crash.strip() else crash[:3000])
app = adb('logcat', '-d', '-s', 'AndroidRuntime:E', 'System.err:W').decode('utf-8', 'ignore')
open(os.path.join(OUT, 'erreurs.txt'), 'w').write(app)
LOG.close()
