import os
import shutil
import sys

# 1. AndroidManifest : vibration + service du mode promeneur
mf = 'android/app/src/main/AndroidManifest.xml'
s = open(mf, encoding='utf-8').read()
if 'android.permission.VIBRATE' not in s:
    k = s.find('<application')
    if k < 0:
        sys.exit('ERREUR patch_walker : balise <application> introuvable')
    s = s[:k] + '    <uses-permission android:name="android.permission.VIBRATE" />\n' + s[k:]
if 'LisieraWalkerService' not in s:
    k = s.find('</application>')
    if k < 0:
        sys.exit('ERREUR patch_walker : balise </application> introuvable')
    s = s[:k] + '    <service android:name=".LisieraWalkerService" android:exported="false" android:foregroundServiceType="location" />\n' + s[k:]
open(mf, 'w', encoding='utf-8').write(s)

# 2. fichiers Java
d = 'android/app/src/main/java/fr/lisiera/app'
os.makedirs(d, exist_ok=True)
for f in ('LisieraWalkerPlugin.java', 'LisieraWalkerService.java'):
    if not os.path.exists(f):
        sys.exit('ERREUR patch_walker : fichier manquant ' + f)
    shutil.copy(f, os.path.join(d, f))

# 3. enregistrement du plugin dans MainActivity
ma = os.path.join(d, 'MainActivity.java')
t = open(ma, encoding='utf-8').read()
if 'LisieraWalkerPlugin' not in t:
    old = 'registerPlugin(LisieraTrackerPlugin.class);'
    if t.count(old) != 1:
        sys.exit('ERREUR patch_walker : registerPlugin(LisieraTrackerPlugin) trouve %d fois (attendu 1)' % t.count(old))
    t = t.replace(old, old + '\n        registerPlugin(LisieraWalkerPlugin.class);')
    open(ma, 'w', encoding='utf-8').write(t)
print('patch_walker OK')
