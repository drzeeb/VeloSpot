# OSM-Fahrradabstellanlagen-Extraktor

Dieses Skript extrahiert alle `amenity=bicycle_parking`-Objekte aus dem deutschen
OpenStreetMap-Datensatz und schreibt sie in eine SQLite-Datenbank, die direkt als
Android-Asset in der VeloSpot-App genutzt werden kann.

## Voraussetzungen

```bash
pip install osmium requests
```

> **Hinweis**: `osmium` (pyosmium) benötigt ggf. native Build-Tools.  
> Auf Windows empfiehlt sich die Installation via conda oder WSL.

## Verwendung

### 1. PBF-Datei herunterladen (optional)

Das Skript kann die Datei automatisch herunterladen (~4 GB):

```bash
python extract_osm_parking.py
# Fragt: "Download germany-latest.osm.pbf from Geofabrik? [y/N]"
```

Alternativ manuell von https://download.geofabrik.de/europe/germany-latest.osm.pbf herunterladen
und den Pfad übergeben:

```bash
python extract_osm_parking.py --pbf /pfad/zu/germany-latest.osm.pbf
```

### 2. Datenbank generieren

```bash
python extract_osm_parking.py \
  --pbf germany-latest.osm.pbf \
  --out ../app/src/main/assets/bike_parking_germany.db
```

Die Standardpfade sind so gesetzt, dass der Aufruf ohne Parameter aus dem
`scripts/`-Verzeichnis heraus funktioniert.

### 3. App neu bauen

Nach dem Generieren einfach die App neu bauen:

```bash
# Im Projekt-Root:
./gradlew assembleDebug
```

Room kopiert das Asset beim ersten Start auf das Gerät.

## Laufzeit & Größe

| Schritt | Zeit (typisch) |
|---------|---------------|
| Download Germany PBF | 10–30 min (je nach Leitung, ~4 GB) |
| Parsing (nur Nodes) | **2–5 min** |
| SQLite-Schreiben | < 1 min |

Die erzeugte Datenbank ist typischerweise **10–20 MB** für ganz Deutschland
(~80.000–130.000 Fahrradabstellanlagen als Nodes).

> **Warum nur Nodes?**  
> Ways/Relations brauchen einen vollständigen Knoten-Koordinaten-Index
> (`locations=True`, `idx="flex_mem"`), der für Deutschland mehrere GB RAM
> belegt und 30–60+ Minuten läuft. Da >95 % aller `bicycle_parking`-Objekte
> als Nodes erfasst sind, lohnt der Aufwand nicht.

## Daten-Mapping

| OSM-Tag | Room-Spalte | Bemerkung |
|---------|-------------|-----------|
| `amenity=bicycle_parking` | – | Filterkriterium |
| `name` | `name` | |
| `capacity` | `capacity` | Als Integer geparst |
| `covered=yes` | `isCovered = 1` | |
| `bicycle_parking=shed/lockers/...` | `type = GARAGE` | |
| `bicycle_parking=stands/rack/...` | `type = BIKE_RACK` | |
| kein `bicycle_parking`-Tag | `type = UNKNOWN` | |
| `operator` | `operator` | |
| `addr:street` + `addr:housenumber` | `address` | |
| Node-ID | `id = osm_n_{id}` | |
| Way-ID (Zentroid) | `id = osm_w_{id}` | |
| Relation-ID (Zentroid) | `id = osm_r_{id}` | |

## Aktualisierung

Geofabrik aktualisiert die Deutschland-Extrakte täglich. Für Updates:

1. Neue PBF herunterladen
2. Skript erneut ausführen
3. Datenbank-Version in `BikeParkingDatabase.kt` inkrementieren
4. Migration oder `fallbackToDestructiveMigration` sicherstellen
5. App neu bauen und verteilen

## Wichtig: Erstinstallation vs. Update

Room's `createFromAsset` kopiert die Asset-Datenbank **nur beim ersten Start**,
wenn keine Datenbank-Datei auf dem Gerät existiert. Bei App-Updates müssen
Nutzer entweder:
- Die App-Daten löschen, **oder**
- Es wird eine Room-Migration implementiert, die die Daten neu einliest.

Für Entwicklungszwecke reicht ein Deinstallieren + Neuinstallieren.

