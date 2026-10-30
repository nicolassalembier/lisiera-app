import os
import re
import sys
# Signature "release" (clé d'importation Play) + numéro de version, pour produire l'AAB
p = 'android/app/build.gradle'
s = open(p, encoding='utf-8').read()
run = int(os.environ.get('GITHUB_RUN_NUMBER', '1'))
code = 100 + run
s, n1 = re.subn(r'versionCode\s*=?\s*\d+', 'versionCode %d' % code, s, count=1)
s, n2 = re.subn(r'versionName\s*=?\s*"[^"]*"', 'versionName "1.0.%d"' % run, s, count=1)
if n1 != 1 or n2 != 1:
    sys.exit('ERREUR patch_release : versionCode/versionName introuvables dans build.gradle')
if 'signingConfigs.release' not in s:
    SIGN = (
        '    signingConfigs {\n'
        '        release {\n'
        "            storeFile file(System.getProperty('user.home') + '/.android/debug.keystore')\n"
        "            storePassword 'android'\n"
        "            keyAlias 'androiddebugkey'\n"
        "            keyPassword 'android'\n"
        '        }\n'
        '    }\n'
    )
    m = re.search(r'^[ \t]*buildTypes\s*\{', s, re.M)
    if not m:
        sys.exit('ERREUR patch_release : bloc buildTypes introuvable dans build.gradle')
    s = s[:m.start()] + SIGN + s[m.start():]
    s, n3 = re.subn(r'(buildTypes\s*\{\s*release\s*\{)', r'\1\n            signingConfig signingConfigs.release\n           ', s, count=1)
    if n3 != 1:
        sys.exit('ERREUR patch_release : bloc buildTypes/release introuvable dans build.gradle')
if 'debuggable' in s:
    print('ATTENTION patch_release : "debuggable" present dans build.gradle')
open(p, 'w', encoding='utf-8').write(s)
print('patch_release OK : versionCode %d, versionName 1.0.%d' % (code, run))
