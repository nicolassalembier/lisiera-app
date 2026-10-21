import sys

p = 'android/app/src/main/AndroidManifest.xml'
s = open(p, encoding='utf-8').read()

perms = [
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
    'android.permission.POST_NOTIFICATIONS',
]
add = ''
for q in perms:
    if q not in s:
        add += '    <uses-permission android:name="%s" />\n' % q
if 'android.hardware.location.gps' not in s:
    add += '    <uses-feature android:name="android.hardware.location.gps" android:required="false" />\n'

i = s.find('<application')
if i < 0:
    sys.exit('ERREUR patch_android : balise <application> introuvable')
s = s[:i] + add + '    ' + s[i:]

LINK = ( 
    ' <intent-filter android:autoVerify="true">\n' 
    ' <action android:name="android.intent.action.VIEW" />\n' 
    ' <category android:name="android.intent.category.DEFAULT" />\n' 
    ' <category android:name="android.intent.category.BROWSABLE" />\n' 
    ' <data android:scheme="https" android:host="lisiera.fr" android:pathPrefix="/join/" />\n' 
    ' </intent-filter>\n' 
) 
if 'android:host="lisiera.fr"' not in s: 
    j = s.find('</activity>') 
    if j < 0: 
        sys.exit('ERREUR patch_android : balise </activity> introuvable') 
    s = s[:j] + LINK + ' ' + s[j:]

MAIN = ('package fr.lisiera.app;\n\n'
        'import android.os.Bundle;\n'
        'import com.getcapacitor.BridgeActivity;\n\n'
        'public class MainActivity extends BridgeActivity {\n'
        '    @Override\n'
        '    public void onCreate(Bundle savedInstanceState) {\n'
        '        registerPlugin(LisieraTrackerPlugin.class);\n'
        '        super.onCreate(savedInstanceState);\n'
        '    }\n'
        '}\n')
if 'android.permission.WAKE_LOCK' not in s:
    k = s.find('<application')
    s = s[:k] + '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n' + s[k:]
if 'LisieraTrackerService' not in s:
    k = s.find('</application>')
    if k < 0:
        sys.exit('ERREUR patch_android : balise </application> introuvable')
    s = s[:k] + '    <service android:name=".LisieraTrackerService" android:exported="false" android:foregroundServiceType="location" />\n' + s[k:]
open(p, 'w', encoding='utf-8').write(s)
import os, shutil
d = 'android/app/src/main/java/fr/lisiera/app'
os.makedirs(d, exist_ok=True)
for f in ('LisieraTrackerPlugin.java', 'LisieraTrackerService.java'):
    shutil.copy(f, os.path.join(d, f))
open(os.path.join(d, 'MainActivity.java'), 'w', encoding='utf-8').write(MAIN)

print('patch_android OK :', len(add.splitlines()), 'ligne(s) ajoutée(s)')
