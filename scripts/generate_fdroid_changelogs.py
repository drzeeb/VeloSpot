"""
One-off generator: writes F-Droid fastlane changelog files (per versionCode, per
locale) distilled from CHANGELOG.md, so the F-Droid "What's New" history is complete
and trackable.

versionCode = major*10000 + minor*100 + patch  (e.g. 1.0.18 -> 10018)

Run from the repo root:  python scripts/generate_fdroid_changelogs.py
Existing files are NOT overwritten unless --force is passed.
"""
from __future__ import annotations
import os
import sys

LOCALES = ("en-US", "de-DE")
BASE = os.path.join("fastlane", "metadata", "android")

# versionCode -> {locale: text}
CHANGELOGS: dict[int, dict[str, str]] = {
    10000: {
        "en-US": (
            "First release of VeloSpot.\n\n"
            "\u2022 100,000+ OpenStreetMap bike-parking spots across Germany \u2013 offline from first launch\n"
            "\u2022 Interactive MapLibre map, favourites and parking photos\n"
            "\u2022 Current-location support and lazy Nominatim reverse geocoding\n"
            "\u2022 Dark mode and 8 languages\n"
            "\u2022 No proprietary Google services, no ads, no tracking\n"
        ),
        "de-DE": (
            "Erste Ver\u00f6ffentlichung von VeloSpot.\n\n"
            "\u2022 100.000+ Fahrradparkpl\u00e4tze aus OpenStreetMap f\u00fcr ganz Deutschland \u2013 offline ab dem ersten Start\n"
            "\u2022 Interaktive MapLibre-Karte, Favoriten und Stellplatzfotos\n"
            "\u2022 Standort-Unterst\u00fctzung und verz\u00f6gerte Nominatim-R\u00fcckw\u00e4rtsgeokodierung\n"
            "\u2022 Dunkelmodus und 8 Sprachen\n"
            "\u2022 Keine propriet\u00e4ren Google-Dienste, keine Werbung, kein Tracking\n"
        ),
    },
    10001: {
        "en-US": (
            "\u2022 Smooth map camera animations (easing) for natural zoom and pan\n"
            "\u2022 Selected marker highlighted in orange\n"
            "\u2022 Better map positioning when a bottom sheet is open\n"
        ),
        "de-DE": (
            "\u2022 Fl\u00fcssige Karten-Kameraanimationen (Easing) f\u00fcr nat\u00fcrliches Zoomen und Schwenken\n"
            "\u2022 Ausgew\u00e4hlter Marker in Orange hervorgehoben\n"
            "\u2022 Bessere Kartenpositionierung bei ge\u00f6ffnetem Bottom-Sheet\n"
        ),
    },
    10002: {
        "en-US": "Maintenance: migrated the CI workflows to the Node 24 action runtime. No user-facing changes.\n",
        "de-DE": "Wartung: CI-Workflows auf die Node-24-Action-Laufzeit migriert. Keine f\u00fcr Nutzer sichtbaren \u00c4nderungen.\n",
    },
    10003: {
        "en-US": "Maintenance: CI release tooling update (action-gh-release v3). No user-facing changes.\n",
        "de-DE": "Wartung: Aktualisierung des CI-Release-Tools (action-gh-release v3). Keine f\u00fcr Nutzer sichtbaren \u00c4nderungen.\n",
    },
    10004: {
        "en-US": (
            "\u2022 Parking spot photos shown in the detail sheet\n"
            "\u2022 Automatic image caching (Coil) for fast, offline-friendly loading\n"
        ),
        "de-DE": (
            "\u2022 Fotos von Stellpl\u00e4tzen im Detail-Sheet\n"
            "\u2022 Automatisches Bild-Caching (Coil) f\u00fcr schnelles, offlinetaugliches Laden\n"
        ),
    },
    10005: {
        "en-US": (
            "\u2022 In-app bike navigation with OSRM route calculation and a route line on the map\n"
            "\u2022 Navigation status card with distance/time and a stop action\n"
            "\u2022 Navigation focus mode dims non-destination markers\n"
            "\u2022 Dark mode preference now persists across restarts\n"
        ),
        "de-DE": (
            "\u2022 In-App-Fahrrad-Navigation mit OSRM-Routenberechnung und Routenlinie auf der Karte\n"
            "\u2022 Navigations-Statuskarte mit Entfernung/Zeit und Stopp-Aktion\n"
            "\u2022 Fokusmodus blendet Nicht-Ziel-Marker ab\n"
            "\u2022 Dunkelmodus-Einstellung bleibt jetzt \u00fcber Neustarts erhalten\n"
        ),
    },
    10006: {
        "en-US": (
            "\u2022 Germany-wide bike-parking coverage from a pre-bundled OpenStreetMap dataset\n"
            "\u2022 Minor refactors and clean-ups\n"
        ),
        "de-DE": (
            "\u2022 Deutschlandweite Fahrradparkplatz-Abdeckung aus einem vorgeb\u00fcndelten OpenStreetMap-Datensatz\n"
            "\u2022 Kleinere Refactorings und Aufr\u00e4umarbeiten\n"
        ),
    },
    10007: {
        "en-US": (
            "\u2022 Offline bike routing with BRouter \u2013 routes calculated fully on-device after a one-time map-segment download (~200\u2013250 MB)\n"
            "\u2022 Five routing profiles (Trekking, Fast, Shortest, MTB, Gravel)\n"
            "\u2022 Wi-Fi check and live download progress; readable duration formatting\n"
        ),
        "de-DE": (
            "\u2022 Offline-Fahrrad-Routing mit BRouter \u2013 Routen vollst\u00e4ndig auf dem Ger\u00e4t nach einmaligem Karten-Download (~200\u2013250 MB)\n"
            "\u2022 F\u00fcnf Routing-Profile (Trekking, Schnell, K\u00fcrzeste, MTB, Gravel)\n"
            "\u2022 WLAN-Pr\u00fcfung und Live-Download-Fortschritt; lesbare Daueranzeige\n"
        ),
    },
    10008: {
        "en-US": (
            "\u2022 New address search bar with live Nominatim suggestions and a result pin\n"
            "\u2022 Map rendering migrated to MapLibre vector tiles \u2013 smooth at every zoom, no API key\n"
            "\u2022 Release builds now minified/obfuscated (R8) with hardened logging\n"
        ),
        "de-DE": (
            "\u2022 Neue Adresssuchleiste mit Live-Vorschl\u00e4gen (Nominatim) und Ergebnis-Pin\n"
            "\u2022 Kartendarstellung auf MapLibre-Vektorkacheln umgestellt \u2013 \u00fcberall scharf, kein API-Key\n"
            "\u2022 Release-Builds jetzt minimiert/obfuskiert (R8) mit geh\u00e4rtetem Logging\n"
        ),
    },
    10010: {
        "en-US": (
            "\u2022 F-Droid: BRouter is now built from source via an srclib instead of a bundled JAR\n"
            "\u2022 Completed missing translations (es, fr, it, lb, nl, pt)\n"
            "\u2022 Fixed a MapView memory leak and a location-callback leak\n"
            "\u2022 CI hardening: wrapper validation, Android Lint, dependency review\n"
        ),
        "de-DE": (
            "\u2022 F-Droid: BRouter wird jetzt per srclib aus dem Quellcode gebaut statt als JAR mitgeliefert\n"
            "\u2022 Fehlende \u00dcbersetzungen erg\u00e4nzt (es, fr, it, lb, nl, pt)\n"
            "\u2022 MapView-Speicherleck und Location-Callback-Leck behoben\n"
            "\u2022 CI geh\u00e4rtet: Wrapper-Pr\u00fcfung, Android-Lint, Dependency-Review\n"
        ),
    },
    10011: {
        "en-US": (
            "\u2022 Map layer toggles: show/hide parking, favourites and saved places\n"
            "\u2022 Dark map tiles in dark mode, with theme-aware markers\n"
            "\u2022 Save custom pins as named favourites (separate, isolated database)\n"
            "\u2022 One-time startup centering on your location\n"
            "\u2022 Modernised app icon\n"
        ),
        "de-DE": (
            "\u2022 Karten-Ebenen umschaltbar: Parkpl\u00e4tze, Favoriten und gespeicherte Orte ein-/ausblenden\n"
            "\u2022 Dunkle Kartenkacheln im Dunkelmodus, mit themengerechten Markern\n"
            "\u2022 Eigene Pins als benannte Favoriten speichern (eigene, isolierte Datenbank)\n"
            "\u2022 Einmaliges Zentrieren auf deinen Standort beim Start\n"
            "\u2022 Modernisiertes App-Icon\n"
        ),
    },
    10012: {
        "en-US": (
            "\u2022 BRouter offline-routing engine upgraded 1.6.3 \u2192 1.7.9 (slimmed on-device build)\n"
            "\u2022 Refreshed bundled routing profiles\n"
            "\u2022 Release workflow now auto-promotes the changelog\n"
        ),
        "de-DE": (
            "\u2022 BRouter-Offline-Routing-Engine von 1.6.3 auf 1.7.9 aktualisiert (schlanker On-Device-Build)\n"
            "\u2022 Aktualisierte mitgelieferte Routing-Profile\n"
            "\u2022 Release-Workflow pflegt das Changelog jetzt automatisch\n"
        ),
    },
    10013: {
        "en-US": (
            "Maintenance: strip the AGP \"Dependency metadata\" signing block from the APK so the F-Droid "
            "scanner accepts the build (kept in the AAB for Google Play). No user-facing changes.\n"
        ),
        "de-DE": (
            "Wartung: Der AGP-Signaturblock \u201eDependency metadata\u201c wird aus dem APK entfernt, damit der "
            "F-Droid-Scanner den Build akzeptiert (im AAB f\u00fcr Google Play bleibt er erhalten). "
            "Keine f\u00fcr Nutzer sichtbaren \u00c4nderungen.\n"
        ),
    },
    10014: {
        "en-US": (
            "\u2022 BRouter is now built from source \u2013 fully reproducible F-Droid build, no binary blob and no prebuild step\n"
            "\u2022 Reproducible release setup: signing via a gitignored keystore.properties / CI secrets\n"
        ),
        "de-DE": (
            "\u2022 BRouter wird jetzt aus dem Quellcode gebaut \u2013 vollst\u00e4ndig reproduzierbarer F-Droid-Build, ohne Bin\u00e4r-Blob und ohne Prebuild-Schritt\n"
            "\u2022 Reproduzierbares Release-Setup: Signierung \u00fcber eine gitignorete keystore.properties / CI-Secrets\n"
        ),
    },
    10018: {
        "en-US": (
            "\u2022 Bike parking now covers France \U0001F1EB\U0001F1F7 and Luxembourg \U0001F1F1\U0001F1FA too\n"
            "\u2022 Multi-country address search, biased to your surroundings\n"
            "\u2022 New About screen (website, dataset dates, privacy, support)\n"
            "\u2022 \"Where did I park?\" \u2013 save your bike's spot, auto-parked on arrival\n"
            "\u2022 Marker clustering for a much smoother map\n"
            "\u2022 Live 3D turn-by-turn navigation with ETA and auto-reroute\n"
            "\u2022 2D/3D map view switch; screen stays awake while navigating\n"
        ),
        "de-DE": (
            "\u2022 Fahrradparkpl\u00e4tze jetzt auch f\u00fcr Frankreich \U0001F1EB\U0001F1F7 und Luxemburg \U0001F1F1\U0001F1FA\n"
            "\u2022 Mehrl\u00e4nder-Adresssuche mit Bias auf deine Umgebung\n"
            "\u2022 Neuer \u201e\u00dcber\u201c-Bereich (Website, Datenst\u00e4nde, Datenschutz, Unterst\u00fctzung)\n"
            "\u2022 \u201eWo hab ich geparkt?\u201c \u2013 Abstellort merken, Auto-Parken bei Ankunft\n"
            "\u2022 Marker-Clustering f\u00fcr eine deutlich fl\u00fcssigere Karte\n"
            "\u2022 Live-3D-Navigation mit Ankunftszeit und automatischer Neuberechnung\n"
            "\u2022 2D/3D-Kartenansicht; Display bleibt w\u00e4hrend der Navigation an\n"
        ),
    },
    10019: {
        "en-US": (
            "\u2022 New cyclist avatar as your live-location marker \u2013 on the map and during navigation\n"
            "\u2022 Record your rides (\"My rides\") with time, distance, speed, elevation and a speed chart\n"
            "\u2022 Pan the map freely while navigating or recording, then tap re-centre to follow again\n"
            "\u2022 Smarter GPS filtering for cleaner tracks and realistic speeds\n"
            "\u2022 Favourites moved to their own isolated database for safer storage\n"
        ),
        "de-DE": (
            "\u2022 Neuer Fahrrad-Avatar als Marker f\u00fcr deinen Standort \u2013 auf der Karte und w\u00e4hrend der Navigation\n"
            "\u2022 Fahrten aufzeichnen (\u201eMeine Fahrten\u201c) mit Zeit, Distanz, Tempo, H\u00f6henmetern und Tempo-Diagramm\n"
            "\u2022 Karte w\u00e4hrend Navigation/Aufzeichnung frei verschieben, dann per Zentrieren-Button wieder folgen\n"
            "\u2022 Bessere GPS-Filterung f\u00fcr saubere Tracks und realistische Geschwindigkeiten\n"
            "\u2022 Favoriten in eigener, isolierter Datenbank \u2013 sicherer gespeichert\n"
        ),
    },
    10020: {
        "en-US": (
            "\u2022 Share your ride as a slick \"VeloSpot Wrapped\" card \u2013 your route on a map cutout with your stats, ready for WhatsApp, Telegram & Instagram, in six colour themes\n"
            "\u2022 Ride recording now keeps running in the background \u2013 with a notification, a Quick Settings tile and a home-screen widget\n"
            "\u2022 The ride timer keeps ticking smoothly; no accidental pins while navigating or recording\n"
            "\u2022 Privacy disclosures and stability fixes\n"
        ),
        "de-DE": (
            "\u2022 Teile deine Fahrt als schicke \u201eVeloSpot Wrapped\u201c-Kachel \u2013 Route auf einem Kartenausschnitt mit deinen Statistiken, perfekt f\u00fcr WhatsApp, Telegram & Instagram, in sechs Farbthemen\n"
            "\u2022 Fahrtaufzeichnung l\u00e4uft jetzt im Hintergrund weiter \u2013 mit Benachrichtigung, Schnelleinstellungs-Kachel und Homescreen-Widget\n"
            "\u2022 Der Timer l\u00e4uft fl\u00fcssig weiter; keine versehentlichen Pins w\u00e4hrend Navigation/Aufzeichnung\n"
            "\u2022 Datenschutz-Hinweise und Stabilit\u00e4tsverbesserungen\n"
        ),
    },
    10021: {
        "en-US": (
            "\u2022 Share any spot as an OpenStreetMap link from the detail sheets\n"
            "\u2022 Cleaner map: one Settings sheet, a slimmer navigation pill with route elevation profile, and a turn-by-turn banner\n"
            "\u2022 Round-trip generator builds a loop back to your start\n"
            "\u2022 Offline routing: download all of Germany, France & Luxembourg, plus automatic on-demand tiles\n"
            "\u2022 Smoother live navigation and several offline-routing fixes\n"
        ),
        "de-DE": (
            "\u2022 Teile jeden Ort als OpenStreetMap-Link aus den Detail-Sheets\n"
            "\u2022 Aufger\u00e4umte Karte: ein einziges Einstellungs-Sheet, schlankere Navigations-Pille mit H\u00f6henprofil und ein Abbiege-Banner\n"
            "\u2022 Rundtour-Generator erstellt eine Schleife zur\u00fcck zum Start\n"
            "\u2022 Offline-Routing: ganz Deutschland, Frankreich & Luxemburg herunterladen, plus automatische Kacheln bei Bedarf\n"
            "\u2022 Fl\u00fcssigere Live-Navigation und mehrere Offline-Routing-Korrekturen\n"
        ),
    },
    10022: {
        "en-US": (
            "\u2022 Ride heatmap overlay turns your recorded rides into a colour heatmap of where you cycle most\n"
            "\u2022 Spoken turn-by-turn voice guidance reads upcoming turns aloud (opt-in)\n"
            "\u2022 New \"Route hilliness\" slider trades a little distance for flatter offline routes\n"
            "\u2022 Routes no longer start on the sidewalk, and round trips work with every cycling profile\n"
            "\u2022 Several offline-routing fixes for more reliable route calculation\n"
        ),
        "de-DE": (
            "\u2022 Fahrten-Heatmap zeigt als farbige Heatmap, wo du am h\u00e4ufigsten f\u00e4hrst\n"
            "\u2022 Gesprochene Abbiegeansagen lesen die n\u00e4chsten Abbiegungen vor (optional)\n"
            "\u2022 Neuer Schieberegler \u201eH\u00fcgeligkeit der Route\u201c f\u00fcr flachere Offline-Routen\n"
            "\u2022 Routen starten nicht mehr auf dem Gehweg, und Rundtouren funktionieren mit jedem Radprofil\n"
            "\u2022 Fl\u00fcssigere Live-Navigation und mehrere Offline-Routing-Korrekturen\n"
        ),
    },
    10022: {
        "en-US": (
            "\u2022 Ride heatmap overlay turns your recorded rides into a colour heatmap of where you cycle most\n"
            "\u2022 Spoken turn-by-turn voice guidance reads upcoming turns aloud (opt-in)\n"
            "\u2022 New \"Route hilliness\" slider trades a little distance for flatter offline routes\n"
            "\u2022 Routes no longer start on the sidewalk, and round trips work with every cycling profile\n"
            "\u2022 Several offline-routing fixes for more reliable route calculation\n"
        ),
        "de-DE": (
            "\u2022 Fahrten-Heatmap zeigt als farbige Heatmap, wo du am h\u00e4ufigsten f\u00e4hrst\n"
            "\u2022 Gesprochene Abbiegeansagen lesen die n\u00e4chsten Abbiegungen vor (optional)\n"
            "\u2022 Neuer Schieberegler \u201eH\u00fcgeligkeit der Route\u201c f\u00fcr flachere Offline-Routen\n"
            "\u2022 Routen starten nicht mehr auf dem Gehweg, und Rundtouren funktionieren mit jedem Radprofil\n"
            "\u2022 Mehrere Offline-Routing-Korrekturen f\u00fcr zuverl\u00e4ssigere Routenberechnung\n"
        ),
    },
}


def main() -> int:
    force = "--force" in sys.argv
    written, skipped, warned = 0, 0, 0
    for code, by_locale in sorted(CHANGELOGS.items()):
        for locale, text in by_locale.items():
            d = os.path.join(BASE, locale, "changelogs")
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, f"{code}.txt")
            if os.path.exists(path) and not force:
                print(f"skip (exists): {path}")
                skipped += 1
                continue
            # F-Droid soft limit is 500 characters per changelog.
            if len(text) > 500:
                print(f"WARNING: {path} is {len(text)} chars (> 500)")
                warned += 1
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
            print(f"wrote: {path} ({len(text)} chars)")
            written += 1
    print(f"\nDone. wrote={written} skipped={skipped} over-limit={warned}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

