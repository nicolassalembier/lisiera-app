import os
import shutil
import sys

# Notifications du fil de battue quand l'application n'est pas affichée (Android).
# Les fichiers Java du dépôt ne sont PAS modifiés : on travaille sur la copie du projet Android généré
# (comme patch_walker.py), donc l'AAB du test fermé n'est pas concerné.
d = 'android/app/src/main/java/fr/lisiera/app'
for f in ('LisieraFeedNotifier.java', 'LisieraFeedPlugin.java'):
    if not os.path.exists(f):
        sys.exit('ERREUR patch_feed : fichier manquant ' + f)
    shutil.copy(f, os.path.join(d, f))

# 1. le service appelle le notificateur après chaque réponse du serveur
ADD = ('private final LisieraFeedNotifier feedN = new LisieraFeedNotifier(this);\n'
       '    private void feedNotify(JSONObject jr) {\n'
       '        try {\n'
       '            feedN.onPing(jr, url, code, token, appVisible);\n'
       '        } catch (Throwable t) {\n'
       '            // sans importance : la position continue d\'être envoyée\n'
       '        }\n'
       '    }\n'
       '    ')
svc = os.path.join(d, 'LisieraTrackerService.java')
s = open(svc, encoding='utf-8').read()
if 'feedNotify(' not in s:
    a = 'handleWalkers(jr);'
    b = 'private void sendOnce() throws Exception {'
    for x in (a, b):
        if s.count(x) != 1:
            sys.exit('ERREUR patch_feed : « %s » trouvé %d fois dans LisieraTrackerService.java (attendu 1)' % (x, s.count(x)))
    s = s.replace(a, a + ' feedNotify(jr);')
    s = s.replace(b, ADD + b)
    open(svc, 'w', encoding='utf-8').write(s)

# 2. le plugin est enregistré dans MainActivity
ma = os.path.join(d, 'MainActivity.java')
t = open(ma, encoding='utf-8').read()
if 'LisieraFeedPlugin' not in t:
    old = 'registerPlugin(LisieraTrackerPlugin.class);'
    if t.count(old) != 1:
        sys.exit('ERREUR patch_feed : registerPlugin(LisieraTrackerPlugin) trouvé %d fois (attendu 1)' % t.count(old))
    t = t.replace(old, old + '\n        registerPlugin(LisieraFeedPlugin.class);')
    open(ma, 'w', encoding='utf-8').write(t)

# 3. contrôle
if 'feedNotify(jr);' not in open(svc, encoding='utf-8').read() or 'LisieraFeedPlugin' not in open(ma, encoding='utf-8').read():
    sys.exit('ERREUR patch_feed : contrôle final échoué')
print('patch_feed OK')
