import os
import plistlib
import re
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else ''


def rep(s, old, new, count=1):
    n = s.count(old)
    if n != count:
        sys.exit('ERREUR patch_ios : motif trouve %d fois (attendu %d) : %s' % (n, count, old[:70]))
    return s.replace(old, new)


if mode == 'web':
    p = sys.argv[2]
    s = open(p, encoding='utf-8').read()

    # 1) Canal iOS : pas de controle de version par APK, pas de plugin Android
    s, n1 = re.subn(r"const APP_BUILD = '[^']*';", "const APP_BUILD = '';", s, count=1)
    s, n2 = re.subn(r"const APP_CHANNEL = '[^']*';", "const APP_CHANNEL = 'ios';", s, count=1)
    if n1 != 1 or n2 != 1:
        sys.exit('ERREUR patch_ios : APP_BUILD / APP_CHANNEL introuvables')
    s = rep(s, "const TrackerPlugin = nativeBG ? CAP.registerPlugin('LisieraTracker') : null;",
            "const TrackerPlugin = null;")

    # 2) Mode promeneur iOS : le plugin natif Android est remplace par l'equivalent JavaScript
    shim = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lisiera_walker_ios.js'), encoding='utf-8').read()
    s = rep(s, "const WCAP = window.Capacitor;", shim + "\nconst WCAP = window.Capacitor;")
    A = ("const wNative = !!(WCAP && typeof WCAP.isNativePlatform === 'function' && WCAP.isNativePlatform() && "
         "(typeof WCAP.getPlatform !== 'function' || WCAP.getPlatform() === 'android'));")
    B = "const wNative = !!(WCAP && typeof WCAP.isNativePlatform === 'function' && WCAP.isNativePlatform());"
    if s.count(A) == 1:
        s = s.replace(A, B)
    elif s.count(B) != 1:
        sys.exit('ERREUR patch_ios : ligne wNative introuvable')
    s = rep(s, "if (wNative) { try { wPlug = WCAP.registerPlugin('LisieraWalker'); } catch (e) { wPlug = null; } }",
            "if (wNative) { try { wPlug = LisieraWalkerIOS; } catch (e) { wPlug = null; } }")

    # 3) Chasseur : permission de notification a l'entree en battue, notification locale quand l'ecran est verrouille
    s = rep(s, "function startNativeGPS() {\n",
            "function startNativeGPS() {\n  try { if (window.__lisAskNotif) window.__lisAskNotif(); } catch (e) {}\n")
    s = rep(s, "  wkSignal(top.info.lvl);\n}\nfunction wkRender() {",
            "  wkSignal(top.info.lvl);\n  try { if (window.__lisNotify) window.__lisNotify(t[0], t[1]); } catch (e) {}\n}\nfunction wkRender() {")

    # 4) Textes : version iPhone exacte (indicateur de localisation iOS, pas de notification permanente)
    s = rep(s, "Une notification permanente avec un bouton « Arrêter » reste affichée pendant ce temps.",
            "iOS affiche un indicateur de localisation en haut de l’écran pendant ce temps ; le bouton « Arrêter la promenade » est dans l’application.", count=2)
    s = rep(s, "Une notification permanente reste affichée pendant la promenade, avec un bouton « Arrêter ». Elle s’arrête d’elle-même au bout de 8 heures.",
            "iOS affiche un indicateur de localisation pendant la promenade. La promenade s’arrête d’elle-même au bout de 8 heures.")
    s = rep(s, "Position en arrière-plan (Android).", "Position en arrière-plan (iPhone).", count=2)
    s = rep(s, "Une notification permanente l’indique. Le partage s’arrête quand je quitte la battue",
            "iOS affiche un indicateur de localisation en haut de l’écran. Le partage s’arrête quand je quitte la battue")
    s = rep(s, "Une notification permanente l’indique, et la promenade s’arrête d’elle-même après 8 heures.",
            "iOS affiche un indicateur de localisation en haut de l’écran, et la promenade s’arrête d’elle-même après 8 heures.")
    s = rep(s, "sur Android même écran éteint (notification)", "sur iPhone même écran verrouillé (notification)")
    s = rep(s, "La notification permanente indique quand mon signalement est actif.",
            "iOS affiche un indicateur de localisation pendant toute la promenade.")
    s = rep(s, "'App Android (arrière-plan)'", "'App iPhone (arrière-plan)'")
    s = rep(s, "'App Android (plugin GPS absent '", "'App iPhone (plugin GPS absent '")
    open(p, 'w', encoding='utf-8').write(s)
    print('patch_ios web OK')

elif mode == 'native':
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
        'Lisiera utilise votre position, y compris écran verrouillé ou application en arrière-plan : '
        'en battue, pour la partager avec les participants de la battue en cours (posté, traqueur, chef) ; '
        'en mode promeneur, pour vous avertir à l’approche d’une battue déclarée. '
        'Le suivi s’arrête quand vous quittez la battue ou la promenade.')
    d['NSCameraUsageDescription'] = (
        'La caméra sert uniquement à la vue caméra (repères affichés en réalité augmentée). '
        'Aucune image n’est enregistrée ni envoyée.')
    d['NSMotionUsageDescription'] = 'Le capteur d’orientation sert à la boussole et à la vue caméra.'
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
