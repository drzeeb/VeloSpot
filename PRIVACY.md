# Datenschutzerklärung für VeloSpot

**Stand: 18. Juni 2026**

Diese Datenschutzerklärung informiert dich darüber, wie die App **VeloSpot** („die App") mit deinen Daten umgeht. Der Schutz deiner Privatsphäre ist uns wichtig. VeloSpot wurde nach dem Grundsatz der Datensparsamkeit entwickelt: Die App benötigt **kein Benutzerkonto**, enthält **keine Werbung** und nutzt **kein Tracking und keine Analyse-Tools**.

---

## 1. Verantwortlicher

Verantwortlich für die Datenverarbeitung im Zusammenhang mit dieser App ist:

**Michael** (Entwickler von VeloSpot)
Kontakt: über das GitHub-Repository
- Issues: https://github.com/drzeeb/VeloSpot/issues
- Repository: https://github.com/drzeeb/VeloSpot

> Bitte ergänze hier bei Bedarf deine vollständigen Kontaktdaten (Name, Anschrift, E-Mail-Adresse), wie sie für deine Veröffentlichung im Google Play Store erforderlich sind.

---

## 2. Grundsatz: Keine zentrale Datensammlung

VeloSpot betreibt **keinen eigenen Server** und speichert **keine personenbezogenen Daten** auf zentralen Systemen. Es werden keine Nutzerprofile erstellt, keine Konten angelegt und keine Daten an den Entwickler übermittelt.

Alle von dir erzeugten Daten (z. B. Favoriten und gespeicherte Orte) verbleiben **ausschließlich lokal auf deinem Gerät**.

---

## 3. Welche Daten werden verarbeitet?

### 3.1 Standortdaten

- **Zweck:** Anzeige deiner aktuellen Position auf der Karte, Zentrieren der Karte auf deinen Standort sowie als Ausgangspunkt für die Fahrrad-Navigation.
- **Berechtigungen:** `ACCESS_FINE_LOCATION` und `ACCESS_COARSE_LOCATION`.
- **Verarbeitung:** Dein Standort wird **nur auf dem Gerät** verarbeitet und **nicht** an den Entwickler oder Dritte übertragen. Die Standortermittlung erfolgt erst, nachdem du die Berechtigung erteilt und die Funktion (z. B. „Mein Standort") aktiv genutzt hast.
- **Freiwilligkeit:** Die Standortberechtigung ist optional. Die App kann auch ohne Standortfreigabe genutzt werden; lediglich die Standort-bezogenen Funktionen stehen dann nicht zur Verfügung.
- **Hinweis (Google-Play-Version):** Zur Standortermittlung werden die Google-Play-Services-Standort-APIs verwendet. Es gelten zusätzlich die [Datenschutzbestimmungen von Google](https://policies.google.com/privacy).

### 3.2 Lokal gespeicherte Daten

Folgende Daten werden ausschließlich **lokal in einer Datenbank/Einstellungen auf deinem Gerät** gespeichert und nicht übertragen:

- Favoriten (markierte Fahrrad-Parkplätze)
- Selbst gespeicherte Orte (eigene Pins)
- Über Nominatim aufgelöste und lokal zwischengespeicherte Adressen
- App-Einstellungen (z. B. gewählte Sprache, Dark-Mode, sichtbare Kartenebenen)

Diese Daten werden gelöscht, sobald du die App deinstallierst oder die App-Daten in den Android-Einstellungen löschst.

### 3.3 Karten- und Parkplatzdaten

Die rund 100.000 Fahrrad-Parkplätze sind als **Offline-Datenbank** in der App enthalten (OpenStreetMap-Daten). Für die Anzeige der Parkplätze ist **keine Internetverbindung** erforderlich, und es werden dabei keine Daten übertragen.

---

## 4. Netzwerkverbindungen an Dritte

Bei Nutzung bestimmter Funktionen baut die App Verbindungen zu externen Diensten auf. Dabei wird technisch bedingt deine **IP-Adresse** an den jeweiligen Diensteanbieter übermittelt; je nach Funktion zusätzlich die nachfolgend genannten Daten. Diese Anbieter sind eigenständig Verantwortliche im Sinne des Datenschutzrechts.

| Dienst | Zweck | Übermittelte Daten | Anbieter / Datenschutz |
| --- | --- | --- | --- |
| **OpenFreeMap** | Laden der Vektor-Kartenkacheln | Angefragter Kartenausschnitt, IP-Adresse | [openfreemap.org](https://openfreemap.org/) |
| **Nominatim (OpenStreetMap)** | Adresssuche & Adressauflösung (Geocoding) | Suchbegriff bzw. Koordinaten, IP-Adresse | [OSM-Datenschutzrichtlinie](https://wiki.osmfoundation.org/wiki/Privacy_Policy) |
| **OSRM** | Berechnung von Routen (sofern verwendet) | Start-/Zielkoordinaten, IP-Adresse | [project-osrm.org](https://project-osrm.org/) |

**Hinweise:**
- Diese Verbindungen erfolgen **nur bei aktiver Nutzung** der jeweiligen Funktion (z. B. beim Verschieben der Karte, einer Adresssuche oder dem Antippen eines Parkplatzes ohne gespeicherte Adresse).
- Die **Fahrrad-Navigation per BRouter** erfolgt **vollständig offline auf deinem Gerät**; hierbei werden keine Routendaten an Dritte übertragen.
- Die App überträgt **keine personenbezogenen Kennungen** (keine Werbe-ID, keine Geräte-ID) an diese Dienste.

---

## 5. Berechtigungen im Überblick

| Berechtigung | Zweck |
| --- | --- |
| `INTERNET` | Laden von Kartenkacheln, Adresssuche/-auflösung und Parkplatzfotos |
| `ACCESS_NETWORK_STATE` | Prüfen, ob eine Netzwerkverbindung besteht |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Anzeige des eigenen Standorts und Navigation (optional) |

---

## 6. Keine Werbung, kein Tracking, keine Analyse

VeloSpot enthält:
- **keine** Werbe-SDKs,
- **keine** Analyse- oder Tracking-Werkzeuge (z. B. kein Google Analytics, kein Firebase Analytics),
- **keine** Weitergabe von Daten zu Werbezwecken.

---

## 7. Datensicherheit

Verbindungen zu externen Diensten erfolgen, wo immer möglich, verschlüsselt über HTTPS. Deine lokal gespeicherten Daten unterliegen den Schutzmechanismen deines Android-Geräts.

---

## 8. Rechtsgrundlagen (DSGVO)

Soweit personenbezogene Daten verarbeitet werden, erfolgt dies auf Grundlage von:
- **Art. 6 Abs. 1 lit. a DSGVO** (Einwilligung) – z. B. für die Nutzung der Standortfunktion;
- **Art. 6 Abs. 1 lit. b DSGVO** (Vertragserfüllung/Bereitstellung der Funktionen) – z. B. für Adresssuche, Kartendarstellung und Routing auf deine Anfrage hin;
- **Art. 6 Abs. 1 lit. f DSGVO** (berechtigtes Interesse) – an der technisch fehlerfreien Bereitstellung der App.

---

## 9. Deine Rechte

Da der Entwickler keine personenbezogenen Daten über dich speichert oder verarbeitet, liegen ihm in der Regel keine Daten zu deiner Person vor, auf die sich Auskunfts-, Berichtigungs- oder Löschansprüche beziehen könnten. Du kannst alle lokal in der App gespeicherten Daten jederzeit selbst löschen, indem du die App-Daten löschst oder die App deinstallierst.

Dir stehen nach der DSGVO grundsätzlich folgende Rechte zu: Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18), Datenübertragbarkeit (Art. 20) und Widerspruch (Art. 21). Zudem hast du das Recht, dich bei einer Datenschutz-Aufsichtsbehörde zu beschweren.

---

## 10. Kinder

VeloSpot richtet sich nicht gezielt an Kinder und erhebt wissentlich keine personenbezogenen Daten von Kindern.

---

## 11. Änderungen dieser Datenschutzerklärung

Diese Datenschutzerklärung kann angepasst werden, wenn sich die App oder rechtliche Anforderungen ändern. Die jeweils aktuelle Fassung ist im Projekt-Repository hinterlegt. Das oben genannte Datum gibt den Stand der letzten Aktualisierung an.

---

## 12. Kontakt

Bei Fragen zum Datenschutz wende dich bitte über das GitHub-Repository an den Entwickler:
https://github.com/drzeeb/VeloSpot/issues

