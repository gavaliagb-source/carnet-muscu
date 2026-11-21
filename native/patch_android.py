"""Ajoute Health Connect au projet Android généré par Capacitor."""
import re, shutil, os
A = 'android'
def rw(p, f):
    s = open(p, encoding='utf-8').read(); n = f(s); assert n != s, p; open(p, 'w', encoding='utf-8').write(n)
rw(f'{A}/variables.gradle', lambda s: re.sub(r'minSdkVersion = \d+', 'minSdkVersion = 26', s))
rw(f'{A}/build.gradle', lambda s: s.replace("classpath 'com.android.tools.build:gradle:", "classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20'\n        classpath 'com.android.tools.build:gradle:", 1))
def app(s):
    s = s.replace("apply plugin: 'com.android.application'", "apply plugin: 'com.android.application'\napply plugin: 'org.jetbrains.kotlin.android'", 1)
    s = s.replace("    buildTypes {", "    kotlinOptions {\n        jvmTarget = JavaVersion.VERSION_21.toString()\n    }\n    buildTypes {", 1)
    s = s.replace("dependencies {", "dependencies {\n    implementation 'androidx.work:work-runtime:2.9.1'\n    implementation 'com.google.android.gms:play-services-wearable:19.0.0'\n    implementation 'androidx.health.connect:connect-client:1.1.0'\n    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0'", 1)
    return s
rw(f'{A}/app/build.gradle', app)
PERMS = ['WRITE_EXERCISE', 'WRITE_ACTIVE_CALORIES_BURNED', 'READ_WEIGHT', 'READ_HEART_RATE', 'WRITE_HEART_RATE', 'WRITE_TOTAL_CALORIES_BURNED']
def man(s):
    ps = ''.join(f'    <uses-permission android:name="android.permission.health.{p}" />\n' for p in PERMS)
    ps += '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
    ps += '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n'
    ps += '    <queries>\n        <package android:name="com.google.android.apps.healthdata" />\n    </queries>\n'
    s = s.replace('</manifest>', ps + '</manifest>', 1)
    act = '''        <activity android:name=".HealthPrivacyActivity" android:exported="true">
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
        </activity>
        <activity-alias
            android:name=".ViewPermissionUsageActivity"
            android:exported="true"
            android:targetActivity=".HealthPrivacyActivity"
            android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity-alias>
'''
    act += '''        <service android:name=".PhoneWearListener" android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:pathPrefix="/rl/" />
            </intent-filter>
        </service>
'''
    act += '''        <receiver android:name=".RLLiveReceiver" android:exported="false" />
        <receiver android:name=".RLWidget" android:exported="false" android:label="Runelift">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/rl_widget_info" />
        </receiver>
'''
    return s.replace('    </application>', act + '    </application>', 1)
rw(f'{A}/app/src/main/AndroidManifest.xml', man)
d = f'{A}/app/src/main/java/app/runelift/'
for f in ['MainActivity.java', 'RLHealthPlugin.kt', 'HealthPrivacyActivity.kt', 'RLWearPlugin.kt', 'PhoneWearListener.kt', 'RLLivePlugin.kt', 'RLLiveReceiver.kt', 'RLWidget.kt', 'RLBgWorker.kt']:
    shutil.copy(f'native/{f}', d + f)
print('Health Connect ajouté')

# Icône et écran de démarrage Runelift
import glob
res = f'{A}/app/src/main/res/'
for f in glob.glob(res + 'drawable*/splash.png'): os.remove(f)
for f in glob.glob(res + 'drawable*/ic_launcher_background.xml') + glob.glob(res + 'drawable-v24/ic_launcher_foreground.xml'): os.remove(f)
for root, _, files in os.walk('native/res'):
    for fn in files:
        rel = os.path.relpath(os.path.join(root, fn), 'native/res'); os.makedirs(os.path.dirname(res + rel), exist_ok=True)
        shutil.copy(os.path.join(root, fn), res + rel)
rw(res + 'values/styles.xml', lambda s: s.replace('''<style name="AppTheme.NoActionBarLaunch" parent="Theme.SplashScreen">
        <item name="android:background">@drawable/splash</item>''', '''<style name="AppTheme.NoActionBarLaunch" parent="Theme.SplashScreen">
        <item name="android:background">@drawable/splash</item>
        <item name="windowSplashScreenBackground">@color/rl_splash_bg</item>
        <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher_foreground</item>
        <item name="postSplashScreenTheme">@style/AppTheme.NoActionBar</item>'''))
# Fond de la fenêtre sombre : les barres du haut et du bas prennent cette couleur (sinon blanches en mode clair)
rw(res + 'values/styles.xml', lambda s: re.sub(r'(<style name="AppTheme\.NoActionBar"[^>]*>)', r'\1\n        <item name="android:windowBackground">@color/rl_splash_bg</item>', s, count=1))
print('Icône et démarrage ajoutés')

# Appli de la montre (Wear OS)
if os.path.isdir(f'{A}/wear'): shutil.rmtree(f'{A}/wear')
shutil.copytree('native/wear', f'{A}/wear')
with open(f'{A}/settings.gradle', 'a', encoding='utf-8') as f: f.write("\ninclude ':wear'\n")
print('Appli montre ajoutée')
