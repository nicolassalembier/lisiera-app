import os
import plistlib
import re
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else ''

if mode == 'web':
    # Page web embarquée : canal iOS, pas de contrôle de version APK, pas de plugin Android
    p = sys.argv[2]
    s = open(p, encoding='utf-8').read()
    s, n1 = re.subn(r"const APP_BUILD = '[^']*';", "const APP_BUILD = '';", s, count=1)
    s, n2 = re.subn(r"const APP_CHANNEL = '[^']*';", "const APP_CHANNEL = 'ios';", s, count=1)
    old = "const TrackerPlugin = nativeBG ? CAP.registerPlugin('LisieraTracker') : null;"
    new = "const TrackerPlugin = (nativeBG && typeof CAP.getPlatform === 'function' && CAP.getPlatform() === 'android') ? CAP.registerPlugin('LisieraTracker') : null;"
    n3 = s.count(old)
    if n1 != 1 or n2 != 1 or n3 != 1:
        sys.exit('ERREUR patch_ios web : motifs trouves %d/%d/%d (attendu 1/1/1)' % (n1, n2, n3))
    s = s.replace(old, new)
    # Textes de consentement : version iPhone (pas de notification permanente, promeneur au premier plan)
    TXT = [
        ('Position en arrière-plan (Android). Tant que je suis', 'Position en arrière-plan (iPhone). Tant que je suis'),
        ('Une notification permanente l’indique. Le partage s’arrête quand je quitte la battue',
         'Un indicateur de localisation s’affiche en haut de l’écran. Le partage s’arrête quand je quitte la battue'),
        ('Position en arrière-plan (Android). Pendant ma promenade, Lisiera utilise la position GPS de mon téléphone '
         '<b>même lorsque l’application n’est pas affichée à l’écran</b> (écran éteint, autre application ouverte), '
         'pour calculer ma distance aux battues déclarées et m’avertir. Par défaut, cette position reste sur mon téléphone. '
         'Une notification permanente l’indique, et la promenade s’arrête d’elle-même après 8 heures.',
         'Mode promeneur (iPhone). Dans cette version, la position n’est utilisée que tant que l’application est ouverte '
         'et l’écran allumé, pour calculer ma distance aux battues déclarées et m’avertir. '
         'Par défaut, cette position reste sur mon téléphone.'),
    ]
    for a, b in TXT:
        if s.count(a) != 1:
            sys.exit('ERREUR patch_ios web : texte introuvable : ' + a[:50])
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8').write(s)
    print('patch_ios web OK')

elif mode == 'native':
    # Info.plist : permissions, localisation en arriere-plan, chiffrement
    p = 'ios/App/App/Info.plist'
    if not os.path.exists(p):
        sys.exit('ERREUR patch_ios native : Info.plist introuvable')
    with open(p, 'rb') as f:
        d = plistlib.load(f)
    d['CFBundleDisplayName'] = 'Lisiera'
    d['NSLocationWhenInUseUsageDescription'] = (
        'Lisiera utilise votre position pour vous situer sur la carte et, en battue, '
        'la partager avec les participants de cette battue.')
    d['NSLocationAlwaysAndWhenInUseUsageDescription'] = (
        'Lisiera utilise votre position, y compris écran éteint ou application en arrière-plan, '
        'pour la partager avec les participants de la battue en cours (posté, traqueur, chef). '
        'Le partage s’arrête quand vous quittez la battue ou quand elle est terminée.')
    d['NSCameraUsageDescription'] = (
        'La caméra sert uniquement à la vue caméra (repères affichés en réalité augmentée). '
        'Aucune image n’est enregistrée ni envoyée.')
    d['NSMotionUsageDescription'] = (
        'Le capteur d’orientation sert à la boussole et à la vue caméra.')
    modes = list(d.get('UIBackgroundModes', []))
    if 'location' not in modes:
        modes.append('location')
    d['UIBackgroundModes'] = modes
    d['ITSAppUsesNonExemptEncryption'] = False
    with open(p, 'wb') as f:
        plistlib.dump(d, f)
    print('patch_ios native OK')

else:
    sys.exit('usage : patch_ios.py web <index.html> | native')
