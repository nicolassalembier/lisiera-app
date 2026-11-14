import os
import re
import sys

# Variante « dev » : même code que la version Play, mais installable À CÔTÉ d'elle.
# On ne change QUE l'applicationId. Le namespace et le package Java restent fr.lisiera.app :
# les fichiers Java copiés par patch_android.py et patch_walker.py continuent donc de compiler.
NEW_ID = 'fr.lisiera.app.dev'
run = int(os.environ.get('GITHUB_RUN_NUMBER', '1'))


def fail(msg):
    sys.exit('ERREUR patch_dev : ' + msg)


# 1. build.gradle : identifiant d'application et numéro de version
p = 'android/app/build.gradle'
s = open(p, encoding='utf-8').read()
s, n = re.subn(r'(applicationId\s*=?\s*)["\']fr\.lisiera\.app["\']', r'\1"' + NEW_ID + '"', s, count=1)
if n != 1:
    fail('applicationId "fr.lisiera.app" introuvable dans build.gradle')
s, n1 = re.subn(r'versionCode\s*=?\s*\d+', 'versionCode %d' % (1000 + run), s, count=1)
s, n2 = re.subn(r'versionName\s*=?\s*"[^"]*"', 'versionName "1.0.dev%d"' % run, s, count=1)
if n1 != 1 or n2 != 1:
    fail('versionCode / versionName introuvables dans build.gradle')
open(p, 'w', encoding='utf-8').write(s)

# 2. AndroidManifest : on retire le lien d'invitation https://lisiera.fr/join/
#    (il est vérifié pour l'appli du test fermé ; la version dev se saisit avec le code à la main)
mf = 'android/app/src/main/AndroidManifest.xml'
m = open(mf, encoding='utf-8').read()
pat = re.compile(r'[ \t]*<intent-filter[^>]*>(?:(?!</intent-filter>).)*?android:host="lisiera\.fr"(?:(?!</intent-filter>).)*?</intent-filter>[ \t]*\n?', re.S)
m, k = pat.subn('', m)
if k != 1:
    fail('filtre du lien d\'invitation trouvé %d fois (attendu 1)' % k)
open(mf, 'w', encoding='utf-8').write(m)

# 3. Nom affiché : « Lisiera Dev »
sx = 'android/app/src/main/res/values/strings.xml'
if not os.path.exists(sx):
    fail('strings.xml introuvable')
t = open(sx, encoding='utf-8').read()
for name, val, must in (('app_name', 'Lisiera Dev', True), ('title_activity_main', 'Lisiera Dev', False), ('custom_url_scheme', NEW_ID, False)):
    t, c = re.subn(r'(<string name="%s"[^>]*>)[^<]*(</string>)' % name, r'\g<1>' + val + r'\g<2>', t, count=1)
    if c != 1 and must:
        fail('<string name="%s"> introuvable' % name)
open(sx, 'w', encoding='utf-8').write(t)

print('patch_dev OK : %s, versionCode %d, versionName 1.0.dev%d' % (NEW_ID, 1000 + run, run))
