# Implementierungsplan: Fahrten zusammenführen ("Merge rides")

> Status: **Nur Recherche & Planung** — es wurde noch kein Code geschrieben.
> Ziel: Mehrere aufgezeichnete Fahrten aus *My rides* zu **einer** Fahrt zusammenführen
> (Anwendungsfall: ein Recording-Bug hat eine reale Fahrt in mehrere Segmente zerlegt).

---

## 1. Ist-Zustand — recherchierte Architektur

### 1.1 Datenmodell & Persistenz

| Schicht | Datei | Rolle |
|---|---|---|
| Room-Entity | `data/local/entity/RecordedRideEntity.kt` | Tabelle `recorded_rides`. Aggregat-Spalten + `pointsJson` (voller GPS-Track als JSON `List<TrackPoint>`). |
| Domain-Modell | `domain/model/RecordedRide.kt` | `RecordedRide` (mit `points`), `RecordedRideSummary` (track-frei), `TrackPoint` (inkl. `segmentStart`), `LiveRideStats`. |
| DAO | `data/local/dao/RecordedRideDao.kt` | `upsert`, `delete`, `getMetaById/ByIds`, gechunktes Lesen von `pointsJson` (`getPointsJsonLength`/`getPointsJsonChunk`), diverse Update-Queries. |
| Repository (Interface) | `domain/repository/RecordedRidesRepository.kt` | `getRide`, `getRides`, `saveRide`, `removeRide`, `setRideArchived`, … |
| Repository (Impl) | `data/repository/RecordedRidesRepositoryImpl.kt` | Moshi-(De)Serialisierung, gechunktes `readPointsJson` (256 KB-Slices gegen das ~2 MB `CursorWindow`-Limit), Entity⇄Domain-Mapping. |

**Felder einer Fahrt** (`RecordedRideEntity`/`RecordedRide`):
`id`, `startedAt`, `endedAt`, `distanceMeters`, `elapsedSeconds`, `movingSeconds`,
`avgSpeedMps`, `maxSpeedMps`, `elevationGainMeters`, `elevationLossMeters`,
`points`/`pointsJson`, `name`, `isMock`, `archivedAt`, `bikeProfileId`,
`sourceRouteId`, `weather`/`weatherJson`.

`TrackPoint`: `latitude`, `longitude`, `timestamp`, `speedMps?`, `altitudeMeters?`,
`accuracyMeters?`, `segmentStart` (markiert Beginn eines **neuen Segments** nach einer
Pause; die Strecke davor ist eine Lücke — nicht gezeichnet, nicht in Distanz/Zeit gezählt,
eigenes `<trkseg>` im GPX-Export).

### 1.2 Statistik-/Analyse-Pipeline (rein, JVM-testbar)

- **`core/tracking/RideTracker.kt`** — Live-Akkumulation von Distanz/Moving-Time/Max-Speed/
  Elevation während der Aufzeichnung (Gates gegen GPS-Ausreißer). Konstante
  `MAX_PLAUSIBLE_SPEED_MPS = 27.0`.
- **`core/tracking/ElevationAccumulator.kt`** — `compute(Iterable<AltitudeSample>) → ElevationResult(gainMeters, lossMeters)`; die **einzige** Wahrheit für Höhenmeter. Wichtig: `breakSegment()` bricht die Kontinuität über Lücken, ohne akkumulierte Werte zu verlieren.
- **`core/analysis/RideAnalysis.kt`** — `analyzeRide(RecordedRide) → RideAnalysis`: Splits, Speed-/Gradient-Histogramm, Stops, Power, TrackQuality. **Rechnet komplett aus dem gespeicherten `RecordedRide`** (Aggregate + Track), keine Extra-Speicherung. Ignoriert Segmente mit `dt > MAX_SEGMENT_GAP_MILLIS (60 s)` für Timing → Lücken zwischen Segmenten stören die abgeleiteten Werte nicht.
- **`core/analysis/BestEfforts.kt`**, **`Climb.kt`**, **`RideAchievements.kt`** — ebenfalls rein aus Track/Aggregaten abgeleitet, in `RideAnalysisViewModel` gecached (keyed auf Track-Identität).
- **`core/analysis/RideRouteFactory.kt`** — Vorbild für eine reine, JVM-testbare Factory (`object` mit `build(...)`).

**Konsequenz:** Splits, Climbs, Best-Efforts, Achievements werden **nicht** persistiert —
sie werden beim Öffnen der Analyse **neu berechnet**. Für den Merge müssen also nur die
**Aggregat-Spalten** und der **`points`-Track** korrekt kombiniert werden; alle abgeleiteten
Analysen ergeben sich danach automatisch.

### 1.3 UI / Plumbing

| Datei | Rolle |
|---|---|
| `feature/map/presentation/sheets/RidesSheet.kt` | Die "My rides"-Timeline. Enthält **bereits einen Mehrfachauswahl-Modus** (`selectionMode`, `selectedIds`, Checkbox pro Zeile, Cancel/Confirm-Bar) — heute für **Export**. Dieses Muster kann für Merge wiederverwendet werden. |
| `feature/map/presentation/sheets/MapBottomSheets.kt` | Verdrahtet `RidesSheet` mit `MapViewModel` (Callbacks `onExportRides`, `onSelectRide`, `onImport`). |
| `feature/map/presentation/sheets/RideDetailSheet.kt` | Detail-Sheet einer einzelnen Fahrt (Rename, Archive, Delete, Save-as-route, Export…). |
| `feature/map/presentation/ride/RideTrackingController.kt` | UI-nahe Ride-Logik: hält `recordedRideSummaries`, `selectRide`, `deleteRide`, `setRideArchived`, `renameRide`, `importRidesAndShowFirst`. Delegiert an das Repository. |
| `feature/map/presentation/MapViewModel.kt` | Fasst die Controller zusammen, exponiert die Callbacks für die Screens. |
| `feature/analysis/presentation/RideAnalysisViewModel.kt` | Lädt nur den Ziel-Track (`getRide`) + track-freie Summaries für Personal Records. |

### 1.4 CursorWindow-Constraint (wichtig!)

Ein dichter Track kann das ~2 MB `CursorWindow`-Limit sprengen. Deshalb gilt im gesamten
Code die Regel: **`pointsJson` nie als ganze Zelle selektieren**. Lesen erfolgt gechunkt
(`readPointsJson`). Ein **Merge summiert die Tracks**, das Ergebnis ist also potenziell
größer als jeder Einzel-Track — der Schreibpfad (`upsert` mit einem großen `pointsJson`)
ist unkritisch (Room-Insert kennt kein CursorWindow-Limit beim Schreiben), aber jeder
spätere Lesepfad muss weiterhin über die gechunkten Reads laufen (tut er bereits).

---

## 2. Was beim Zusammenführen kombiniert / neu berechnet werden muss

Gegeben: `n` Quell-Fahrten, **nach `startedAt` aufsteigend sortiert** (S₁, S₂, …, Sₙ).

| Feld | Merge-Regel |
|---|---|
| `points` | Tracks in zeitlicher Reihenfolge aneinanderhängen. **Der erste Punkt jedes Folgesegments erhält `segmentStart = true`** → die Lücke zwischen zwei Fahrten wird als Pause/Gap behandelt (nicht gezeichnet, nicht in Distanz/Zeit gezählt). Innerhalb einer Quell-Fahrt vorhandene `segmentStart`-Flags bleiben erhalten. |
| `startedAt` | `min(startedAt)` = S₁.startedAt. |
| `endedAt` | `max(endedAt)` = Sₙ.endedAt. |
| `distanceMeters` | **Summe** der Quell-Distanzen (die Lücke wird nicht mitgezählt, da Gap). |
| `movingSeconds` | Summe der `movingSeconds`. |
| `elapsedSeconds` | Summe der `elapsedSeconds` (= bewegte + gestoppte Zeit **innerhalb** der Fahrten; die Lücken dazwischen bleiben ausgeschlossen — konsistent mit dem Pausen-Modell). **Offene Frage 3.** |
| `avgSpeedMps` | Neu: `distanceMeters / movingSeconds` (0 wenn movingSeconds = 0). |
| `maxSpeedMps` | `max` der Quell-`maxSpeedMps` (bzw. Max über alle `points.speedMps` im plausiblen Bereich `0..MAX_PLAUSIBLE_SPEED_MPS`). |
| `elevationGainMeters` / `elevationLossMeters` | **Neu berechnen** mit `ElevationAccumulator.compute(...)` über den zusammengefügten Track (mit Segmentbrüchen), damit die Lücken keine Phantom-Stufe banken. Fallback: Summe der Quellwerte, falls keine Höhen vorhanden. |
| `name` | Standard: Name der ersten Fahrt; sonst leer (fällt in der UI aufs Datum zurück). Optional editierbar im Bestätigungsdialog. **Offene Frage 5.** |
| `isMock` | Merge nur zwischen gleichartigen Fahrten zulassen; Ergebnis = `isMock` der Quellen (siehe Validierung). |
| `bikeProfileId` | Übernehmen, **wenn alle Quellen dasselbe** Profil haben, sonst `null`. **Offene Frage 4.** |
| `sourceRouteId` | `null` (die zusammengeführte Fahrt entspricht keiner einzelnen geplanten Route). |
| `weather` | Snapshot der ersten Fahrt mit vorhandenem Wetter, sonst `null`. |
| `archivedAt` | `null` (die neue Fahrt landet aktiv in der Timeline). |
| `id` | Neue `UUID`. |

**Neuberechnung abgeleiteter Statistiken:** *keine explizite Aktion nötig* — Splits, Climbs,
Best-Efforts, Gradient-Histogramm, Achievements werden beim Öffnen der Analyse aus dem
gemergten `RecordedRide` neu berechnet. Der Merge muss nur die Aggregat-Spalten + den Track
korrekt schreiben.

---

## 3. Knifflige Punkte & offene Entscheidungen

1. **Zeitliche Lücke zwischen Segmenten**
   → Empfehlung: als **Pause** behandeln (Segmentbruch via `segmentStart = true` am ersten
   Punkt jedes Folgesegments). Damit greifen die vorhandenen 60 s-Gap-Filter in
   `RideAnalysis`/`splitIntoSegments`, die Karte zeichnet keine Luftlinie über die Lücke,
   und der GPX-Export schreibt getrennte `<trkseg>`.

2. **Reihenfolge / Sortierung** der ausgewählten Fahrten
   → Immer **nach `startedAt` aufsteigend** mergen, unabhängig von der Auswahlreihenfolge.
   Deterministisch und für den Bug-Fall (chronologische Segmente) korrekt.

3. **Definition von `elapsedSeconds`** — **offene Frage:**
   (a) Summe der Einzel-`elapsedSeconds` (Lücken ausgeschlossen, konsistentes Pausen-Modell), **oder**
   (b) `endedAt − startedAt` der Gesamtspanne (Lücken als Standzeit eingerechnet).
   → Empfehlung: (a), damit Ø-/Bewegungswerte sauber bleiben.

4. **Bike-Profil-Zuordnung** bei unterschiedlichen Profilen — **offene Frage:** übernehmen
   nur wenn identisch, sonst `null` (Empfehlung), oder Nutzer im Dialog wählen lassen.

5. **Name der Ergebnis-Fahrt** — **offene Frage:** ersten Namen übernehmen (Empfehlung) vs.
   Eingabefeld im Bestätigungsdialog.

6. **Quell-Fahrten behalten oder löschen?** — **offene Frage:**
   Empfehlung: die neue Fahrt **anlegen** und die Quell-Fahrten **archivieren** (nicht hart
   löschen) → faktisches "Undo" möglich (Restore der Originale, neue Fahrt löschen).
   Alternative: hart löschen (aufräumender, aber unumkehrbar).

7. **Validierung / Zulässigkeit des Merges:**
   - Mindestens **2** Fahrten ausgewählt.
   - Keine **Mock**- mit **realen** Fahrten mischen (`isMock` muss übereinstimmen); Mock-Merges evtl. ganz verbieten.
   - **Zeitliche Überlappung** (Segmente überlappen sich zeitlich) → warnen bzw. trotzdem
     zulassen, aber immer chronologisch verketten. (Für den Bug-Fall überlappen sie nicht.)
   - Archivierte Fahrten: entweder aus der Auswahl ausschließen oder mit einbeziehen — **offene Frage 8.**

8. **CursorWindow beim Lesen der Quellen:** die Quell-Tracks über `repository.getRides(ids)`
   laden — dieser Pfad liest bereits gechunkt. Nichts Neues nötig; nur nicht selbst `SELECT *`.

9. **Große Merges / Performance:** das Zusammenfügen + `ElevationAccumulator.compute` läuft auf
   `Dispatchers.Default` (wie der restliche Track-Code), nicht auf dem Main-Thread.

---

## 4. UI / UX-Entwurf

Wiederverwendung des bestehenden **Mehrfachauswahl-Musters** in `RidesSheet.kt` (heute für Export).

1. **Einstieg:** Neuer Button "Merge / Zusammenführen" im Kopfbereich der `RidesSheet`
   (neben *Import* / *Export*). Startet einen `mergeSelectionMode` (analog `selectionMode`).
   - Alternative/ergänzend: langer Druck auf eine Ride-Zeile startet den Auswahlmodus.
2. **Auswahl:** Checkboxen pro Zeile (bereits vorhanden), Zähler im Header
   ("n ausgewählt"). Confirm-Button erst ab **2** Auswahlen aktiv.
3. **Bestätigungsdialog** (`AlertDialog`, analog `ExportDestinationDialog`):
   - Titel "Fahrten zusammenführen?"
   - **Vorschau** der berechneten Ergebniswerte: Gesamt-Distanz, -Dauer, Höhenmeter, Zeitspanne,
     Anzahl Segmente (rein aus einem `MergeRidesUseCase.preview(...)` berechnet, ohne zu speichern).
   - Hinweis, was mit den Originalen passiert (Archivierung/Löschung — je nach Entscheidung 6).
   - Optional: Namensfeld (Entscheidung 5).
   - Buttons: *Zusammenführen* / *Abbrechen*.
4. **Nach dem Merge:** neue Fahrt wird gespeichert, Auswahlmodus verlassen, Detail-Sheet der
   neuen Fahrt öffnen (wie `importRidesAndShowFirst` es tut). Snackbar
   "Fahrten zusammengeführt" mit optionaler **Undo**-Aktion (stellt Originale wieder her /
   löscht die neue Fahrt) — nur sinnvoll, wenn Originale archiviert statt gelöscht (Entscheidung 6).

---

## 5. Betroffene Dateien / Klassen (nach Schicht)

### Neu

- **`core/analysis/RideMerger.kt`** — reine, Android-freie, JVM-testbare Merge-Logik.
  `object RideMerger { fun merge(rides: List<RecordedRide>, newId: String): RecordedRide;
  fun preview(rides: List<RecordedRide>): MergePreview }` (Vorbild: `RideRouteFactory`).
  Verantwortlich für Sortierung, Track-Verkettung + Segmentbrüche, Aggregat-Neuberechnung
  (nutzt `ElevationAccumulator.compute` und `RideTracker.MAX_PLAUSIBLE_SPEED_MPS`).
- **`app/src/test/.../core/analysis/RideMergerTest.kt`** — JVM-Unit-Tests (siehe §6).

### Geändert

| Schicht | Datei | Änderung |
|---|---|---|
| Repository (Interface) | `domain/repository/RecordedRidesRepository.kt` | Optional: `suspend fun mergeRides(ids: List<String>): RecordedRide?` als Convenience (kann auch komplett im Controller orchestriert werden). Default-Impl no-op für Test-Fakes. |
| Repository (Impl) | `data/repository/RecordedRidesRepositoryImpl.kt` | Falls `mergeRides` hier landet: Quellen via `getRides(ids)` laden, `RideMerger.merge` aufrufen, `saveRide` + Originale archivieren/löschen (transaktional erwägen). |
| Controller | `feature/map/presentation/ride/RideTrackingController.kt` | Neue Methode `mergeRides(ids: List<String>)`: lädt Quellen off-main, ruft `RideMerger`, speichert, archiviert/löscht Originale, öffnet Ergebnis-Sheet, sendet User-Message. |
| ViewModel | `feature/map/presentation/MapViewModel.kt` | Callback/Delegation `mergeRecordedRides(ids)` → Controller. |
| UI (Timeline) | `feature/map/presentation/sheets/RidesSheet.kt` | Merge-Auswahlmodus, Merge-Button, Bestätigungs-/Vorschaudialog. |
| UI (Wiring) | `feature/map/presentation/sheets/MapBottomSheets.kt` | `onMergeRides`-Callback an `RidesSheet` durchreichen. |
| Strings | `res/values*/strings.xml` (**8 Locales**) | Neue `ride_merge_*`-Strings (siehe §7). |

### Nicht betroffen (bewusst)

- **Room-Schema / Migration:** **keine** — es werden nur `upsert`/`delete`/`updateArchivedAt`
  (bestehende Queries) genutzt. **Kein neues Feld, keine neue Migration.**
- Analyse-Pipeline (`RideAnalysis`, `BestEfforts`, `Climb`, `RideAchievements`),
  GPX-Export/Import, Bike-Garage: unverändert — sie rechnen automatisch mit der neuen Fahrt.

---

## 6. Teststrategie

**Reine JVM-Unit-Tests** (`RideMergerTest.kt`), keine Android-Abhängigkeit:

1. Zwei einfache Fahrten → Distanz = Summe, `movingSeconds` = Summe, `startedAt` = min,
   `endedAt` = max.
2. Reihenfolge: absichtlich unsortierte Eingabe → Ergebnis chronologisch verkettet.
3. Segmentbruch: erster Punkt jedes Folgesegments hat `segmentStart = true`; innerhalb einer
   Quelle vorhandene `segmentStart` bleiben erhalten.
4. `maxSpeedMps` = Max über Quellen / plausibler Punkt-Speeds.
5. Elevation: über zusammengefügten Track neu berechnet (Lücke bankt keine Phantom-Stufe) —
   deterministisch gegen `ElevationAccumulator.compute`.
6. `avgSpeedMps` = distance/movingSeconds (inkl. movingSeconds = 0 → 0.0).
7. `bikeProfileId`: identisch → übernommen; unterschiedlich → `null`.
8. Edge-Cases: 1 Fahrt (Merge nicht erlaubt / gibt Quelle zurück), leere Liste → `null`,
   Fahrten ohne Höhenpunkte, Mock/real-Mix wird abgelehnt.
9. `preview(...)` liefert dieselben Aggregate wie `merge(...)`.

**Optional (androidTest):** DAO-Round-Trip eines großen gemergten Tracks (multi-Chunk) über
`getRide` — wahrscheinlich schon durch bestehende `RecordedRidesRepositoryImplTest`-Fälle
für große Tracks abgedeckt; nur ergänzen, wenn `mergeRides` im Repository landet.

**Verifikation (PowerShell):**
```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug
```
Zusätzlich `get_errors` auf allen berührten Dateien.

---

## 7. Lokalisierung

Neue, benutzersichtbare Strings in **allen 8 Locales**
(`values`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-lb`, `values-nl`, `values-pt`):

- `ride_merge` — Button "Merge" / "Zusammenführen"
- `ride_merge_select_hint` — Header-Titel im Auswahlmodus
- `ride_merge_selected_count` — "%d ausgewählt" (Plural-fähig prüfen)
- `ride_merge_confirm` / `ride_merge_cancel`
- `ride_merge_dialog_title` / `ride_merge_dialog_text`
- `ride_merge_preview_*` (Distanz, Dauer, Höhenmeter, Segmente) — falls Vorschau
- `ride_merge_done` — Snackbar "Fahrten zusammengeführt"
- `ride_merge_undo` — Undo-Aktion (falls Originale archiviert)
- `ride_merge_min_selection` — Hinweis "mindestens 2 Fahrten"
- ggf. `ride_merge_incompatible` — Fehlermeldung bei Mock/real-Mix

---

## 8. Phasen / PR-Aufteilung

**PR 1 — Reine Merge-Logik (Kern, testbar)**
- `core/analysis/RideMerger.kt` + `RideMergerTest.kt`.
- Keine UI, kein DB-Zugriff. Vollständig JVM-getestet.
- Klärt vorab die offenen Fragen 3–6 (Verhalten der Logik).

**PR 2 — Repository-/Controller-Verdrahtung**
- `mergeRides(ids)` in `RideTrackingController` (+ optional Repository-Methode).
- Laden der Quellen (gechunkt), Speichern, Originale archivieren/löschen, Ergebnis-Sheet öffnen.
- `MapViewModel`-Delegation. Test in `RideTrackingControllerTest` (Fake-Repository).

**PR 3 — UI in "My rides"**
- Merge-Auswahlmodus + Button in `RidesSheet`, Bestätigungs-/Vorschaudialog, Snackbar/Undo.
- Wiring in `MapBottomSheets`.
- Neue Strings in allen 8 Locales.

(PR 2 und 3 können bei Bedarf zusammengelegt werden; PR 1 sollte eigenständig bleiben.)

---

## 9. Risiken & Edge-Cases (Zusammenfassung)

- **Datenverlust bei hartem Löschen der Originale** → Empfehlung: archivieren + Undo.
- **Zeitliche Überlappung** der Quellen (theoretisch) → chronologisch verketten; ggf. warnen.
- **Sehr große gemergte Tracks** → Lesepfad bleibt gechunkt (bestehend); Merge-Rechnung off-main.
- **Gemischte `bikeProfileId` / `isMock`** → klare Regeln (identisch übernehmen / Mix ablehnen).
- **`sourceRouteId` / Leaderboard** → bewusst `null`, um keine falsche Routen-Zuordnung zu erben.
- **`weather`** → nur ein Snapshot sinnvoll; erster vorhandener wird übernommen.
- **Kein Schema-Change** → keine Migration, kein Persistenz-Spezialisten-Koordinationsaufwand.

---

## 10. Getroffene Entscheidungen (vom Nutzer bestätigt)

1. **Originale nach Merge:** **archivieren** (nicht hart löschen) → Undo möglich. ✅
2. **Ergebnis-Name:** Beim Klick auf "Merge" erscheint ein **Dialog zur Eingabe des neuen Namens**. ✅
3. **Bike-Profil bei Unterschieden:** Wenn sich die Profile der Quellen unterscheiden, wird das
   **visuell dargestellt** (Hinweis/Badge im Merge-Dialog, z. B. Liste der beteiligten Profile);
   das Ergebnis-Profil bleibt `null`, wenn nicht alle identisch sind. ✅
4. **Zeitliche Lücke zwischen Fahrten:** als **Pause** behandeln (Segmentbruch via
   `segmentStart = true`). ✅
5. **Auswahl:** **beliebige** Fahrten mergefähig (nicht nur direkt aufeinanderfolgende),
   immer chronologisch nach `startedAt` verkettet. ✅

**Weitere Festlegungen (Empfehlungen übernommen):**
- `elapsedSeconds` = Summe der Einzelzeiten (Lücken ausgeschlossen, konsistentes Pausen-Modell).
- Mock/real-Mix wird abgelehnt; Vorschau der Ergebniswerte wird im Dialog angezeigt.


