# Privacy Policy for VeloSpot

**Last updated: June 21, 2026**

This privacy policy explains how the **VeloSpot** app ("the app") handles your data. Protecting your privacy is important to us. VeloSpot was built on the principle of data minimization: the app requires **no user account**, contains **no advertising**, and uses **no tracking or analytics tools**.

---

## 1. Data Controller

The party responsible for data processing in connection with this app is:

**Michael Zeeb** (developer of VeloSpot)
c/o POSTFLEX PFX-876-986
Emsdettener Straße 10
48268 Greven, Germany

- Mail: velospot@proton.me
- Issues: https://github.com/drzeeb/VeloSpot/issues
- Repository: https://github.com/drzeeb/VeloSpot

See also the full [legal notice (Impressum)](./IMPRINT.md).

---

## 2. Principle: No Central Data Collection

VeloSpot operates **no server of its own** and stores **no personal data** on central systems. No user profiles are created, no accounts are set up, and no data is transmitted to the developer.

All data you generate (e.g. favorites and saved places) remains **exclusively on your device**.

---

## 3. What Data Is Processed?

### 3.1 Location Data

- **Purpose:** Displaying your current position on the map, centering the map on your location, and as the starting point for bike navigation.
- **Permissions:** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`.
- **Processing:** Your location is processed **only on the device** and is **not** transmitted to the developer or third parties. Location detection only happens after you have granted the permission and actively use the feature (e.g. "My Location").
- **Voluntariness:** The location permission is optional. The app can also be used without sharing your location; only location-based features will then be unavailable.
- **Note (Google Play version):** The Google Play Services location APIs are used to determine your location. The [Google Privacy Policy](https://policies.google.com/privacy) additionally applies.

### 3.2 Locally Stored Data

The following data is stored **exclusively locally in a database/settings on your device** and is not transmitted:

- Favorites (marked bike parking spots)
- Self-saved places (custom pins)
- Addresses resolved via Nominatim and cached locally
- App settings (e.g. selected language, dark mode, visible map layers)

This data is deleted as soon as you uninstall the app or clear the app data in your Android settings.

### 3.3 Map and Parking Data

The roughly 100,000 bike parking spots are included in the app as an **offline database** (OpenStreetMap data). Displaying the parking spots requires **no internet connection**, and no data is transmitted in the process.

### 3.4 Ride Sharing (Share Card)

- **Purpose:** When you tap "Share ride" on a recorded ride, the app generates an image (a "share card") that shows a map cutout of that ride's route together with the date and ride statistics (distance, time, speed, elevation).
- **Map cutout:** To draw the map behind the route, the app loads map tiles for the ride's area from **OpenFreeMap** (see Section 4), just like the normal map view. If you are offline, a plain coloured background is used instead and no tiles are requested.
- **Storage:** The generated image is stored **temporarily in the app's private cache** on your device and is **not** transmitted anywhere by VeloSpot.
- **Sharing is user-initiated:** The image leaves your device only when **you** actively choose a target app (e.g. a messenger or social network) in the Android share dialog. From that point on, the image — including the route and statistics it contains — is handled by the app you selected, and **that provider's own privacy policy applies**. Please choose your sharing target consciously, as a route can reveal location information (for example a start or end point near your home).

---

## 4. Network Connections to Third Parties

When you use certain features, the app establishes connections to external services. For technical reasons, this transmits your **IP address** to the respective service provider; depending on the feature, additional data listed below is transmitted. These providers are independent data controllers under data protection law.

| Service | Purpose | Transmitted Data | Provider / Privacy |
| --- | --- | --- | --- |
| **OpenFreeMap** | Loading vector map tiles (map view & ride-share map snapshot), and — if you enable the optional *Offline map* — pre-downloading the tiles for a chosen region | Requested map area, IP address | [openfreemap.org](https://openfreemap.org/) |
| **Nominatim (OpenStreetMap)** | Address search & address resolution (geocoding) | Search term or coordinates, IP address | [OSM Privacy Policy](https://wiki.osmfoundation.org/wiki/Privacy_Policy) |
| **OSRM** | Calculating routes (online fallback, if used) | Start/destination coordinates, IP address | [project-osrm.org](https://project-osrm.org/) |
| **BRouter** | One-time download of offline routing data (map-segment tiles) | Requested 5°×5° tile (≈ your region), IP address | [brouter.de](https://brouter.de/) |

**Notes:**
- These connections only occur **when the respective feature is actively used** (e.g. when panning the map, performing an address search, or tapping a parking spot without a stored address).
- **Bike navigation via BRouter** computes routes **entirely offline on your device** — no start, destination or route is ever transmitted. Only the **one-time download** of the offline routing data fetches map-segment tiles from brouter.de; the requested tile name reveals only your approximate region.
- **Ride-share card:** Generating the share card loads OpenFreeMap tiles for the recorded ride's area (its bounding box) to draw the map cutout; this only happens while you preview the card and only when online. The card image itself is created locally — see Section 3.4 for how it is shared.
- The app transmits **no personal identifiers** (no advertising ID, no device ID) to these services.

---

## 5. Permissions Overview

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Loading map tiles, address search/resolution, and one-time offline-routing data download |
| `ACCESS_NETWORK_STATE` | Checking whether a network connection exists |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Displaying your own location and navigation (optional) |

---

## 6. No Advertising, No Tracking, No Analytics

VeloSpot contains:
- **no** advertising SDKs,
- **no** analytics or tracking tools (e.g. no Google Analytics, no Firebase Analytics),
- **no** sharing of data for advertising purposes.

---

## 7. Data Security

Connections to external services use encrypted HTTPS wherever possible. Your locally stored data is protected by your Android device's security mechanisms.

---

## 8. Legal Bases (GDPR)

Insofar as personal data is processed, this is done on the basis of:
- **Art. 6(1)(a) GDPR** (consent) – e.g. for using the location feature;
- **Art. 6(1)(b) GDPR** (performance of a contract / provision of features) – e.g. for address search, map display, and routing at your request;
- **Art. 6(1)(f) GDPR** (legitimate interest) – in the technically flawless provision of the app.

---

## 9. Your Rights

Because the developer does not store or process any personal data about you, generally no data relating to you is held that could be subject to rights of access, rectification, or erasure. You can delete all data stored locally in the app at any time yourself by clearing the app data or uninstalling the app.

Under the GDPR you are generally entitled to the following rights: access (Art. 15), rectification (Art. 16), erasure (Art. 17), restriction of processing (Art. 18), data portability (Art. 20), and objection (Art. 21). You also have the right to lodge a complaint with a data protection supervisory authority.

---

## 10. Children

VeloSpot is not specifically directed at children and does not knowingly collect personal data from children.

---

## 11. Changes to This Privacy Policy

This privacy policy may be amended if the app or legal requirements change. The current version is kept in the project repository. The date stated above indicates the date of the last update.

---

## 12. Contact

If you have any questions about data protection, please contact the developer via the GitHub repository:
https://github.com/drzeeb/VeloSpot/issues

