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
open(p, 'w', encoding='utf-8').write(s)
print('patch_android OK :', len(add.splitlines()), 'ligne(s) ajoutée(s)')
