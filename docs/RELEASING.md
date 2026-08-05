# Releasing VeloSpot (Google Play)
VeloSpot ships to the **Google Play Store**. The release is fully automated: pushing
a `vX.Y.Z` Git tag builds a signed **App Bundle (AAB)**, publishes a GitHub Release
(with a sideload APK + SBOM + provenance) and — if the Play service-account secret is
configured — uploads the AAB and store metadata to Google Play via **fastlane**.
---
## 1. One-time setup after cloning
BRouter (the offline routing engine) is compiled from source via a **pinned git
submodule**. Initialise it once:
```bash
git clone https://github.com/drzeeb/VeloSpot.git
cd VeloSpot
git submodule update --init --recursive
```
If you forget this step, the build fails fast with a clear message telling you to
run the command above.
### Pinning / bumping BRouter
The submodule (`brouter-upstream/`) is locked to a specific commit (BRouter
`v1.7.9`). To bump BRouter later:
```bash
cd brouter-upstream
git fetch --tags
git checkout v1.7.10        # or the desired tag/commit
cd ..
git add brouter-upstream
git commit -m "chore: bump BRouter to v1.7.10"
```
---
## 2. Building locally
```bash
# JDK 17 must be active (java -version -> 17)
./gradlew bundleRelease      # AAB -> app/build/outputs/bundle/release/app-release.aab
./gradlew assembleRelease    # APK -> app/build/outputs/apk/release/app-release.apk
```
Signing credentials are resolved from a gitignored `keystore.properties` or from
environment variables (see section 3). Without them the build falls back to debug signing.
---
## 3. Signing key management
### Where credentials come from
The release `signingConfig` reads credentials in this order:
1. **`keystore.properties`** at the repo root (for local releases) — *gitignored*.
2. **Environment variables** (CI) — `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD`.
`keystore.properties` (never commit this):
```properties
storeFile=/absolute/path/to/release.jks
storePassword=********
keyAlias=velospot
keyPassword=********
```
`keystore.properties`, `*.jks`, `*.keystore`, `*.p12` and `*.base64` are all listed
in `.gitignore`.
### Play App Signing
With **Play App Signing** (recommended), Google holds the final app-signing key and
your `release.jks` is only the **upload key**. Losing the upload key is recoverable
(you can request a reset in the Play Console); the final signing key stays safe with
Google. Back up the upload key and its passwords anyway (password manager + encrypted
offsite copy).
---
## 4. Cutting a release (GitHub Actions)
The release **content** (version bump + changelog) is prepared **in the release PR**,
*before* tagging. The `.github/workflows/release.yml` workflow then **builds, signs,
publishes the GitHub Release and uploads to Google Play** — it never rewrites files
or moves the tag.
### Step 1 — Prepare the release PR (off `main`)
In a normal PR to `main`, do all three:
1. **Bump the version** in `app/build.gradle.kts`
   (`versionCode = X*10000 + Y*100 + Z`, `versionName = "X.Y.Z"` — keep them literal;
   the workflow greps them to verify they match the tag).
2. **Promote the changelog**: rename the `## [Unreleased]` section to
   `## [vX.Y.Z] — YYYY-MM-DD` and insert a fresh, empty `## [Unreleased]` above it.
3. **Add the Play "What''s New" files** (uploaded as the Play release notes):
   `fastlane/metadata/android/de-DE/changelogs/<versionCode>.txt` and
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
Get the PR reviewed, green and **merged** into `main`.
### Step 2 — Tag the merge commit and push
```bash
git checkout main
git pull --ff-only
git tag vX.Y.Z          # on the merged release commit
git push origin vX.Y.Z
```
### Step 3 — The workflow builds & publishes
On the `vX.Y.Z` tag push, `release.yml`:
- checks out the repo **with submodules**,
- **verifies** the static `versionCode` / `versionName` literals match the tag
  (fails fast on mismatch),
- runs `bundleRelease` + `assembleRelease`, signs with the keystore decoded from
  `KEYSTORE_BASE64`,
- generates the CycloneDX SBOM + build-provenance attestations,
- creates the GitHub Release (AAB + `VeloSpot-vX.Y.Z.apk` + SBOM),
- **uploads the AAB + store metadata to Google Play** with fastlane (only if the
  `PLAY_SERVICE_ACCOUNT_JSON` secret is set).
By default the Play upload targets the **`production`** track with status **`draft`**:
a release is created in the Play Console but not rolled out, so you review it and
press **Publish**. To pick a different track/status (e.g. `internal` / `completed`),
start the workflow manually via **Run workflow** and choose the inputs.
### Required GitHub Actions secrets
Set these once under **Settings -> Secrets and variables -> Actions**:
| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `release.jks` (the upload key) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | signing key alias |
| `KEY_PASSWORD` | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play service-account JSON key (Play upload) |
Generate `KEYSTORE_BASE64` (one-time):
```bash
# macOS / Linux
base64 -i release.jks | tr -d '\n'
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```
---
## 5. Google Play service account (one-time)
To let CI upload builds, create a service account with Play Developer API access:
1. In the **Google Play Console -> Users and permissions**, invite a service account
   (or create one in **Google Cloud Console -> IAM & Admin -> Service Accounts**, then
   grant it access in the Play Console with the *Release* permissions on the app).
2. Create a **JSON key** for that service account and download it.
3. Store the JSON as the `PLAY_SERVICE_ACCOUNT_JSON` GitHub secret.
The upload itself is driven by fastlane `supply` (see `fastlane/Fastfile`), which
reads the store listing and per-version changelogs from `fastlane/metadata/android`.
### Uploading store metadata / screenshots manually
The release workflow skips screenshot upload (slow/flaky). When the listing or
screenshots change, push them explicitly:
```bash
PLAY_SERVICE_ACCOUNT_JSON="$(cat play-sa.json)" \
  bundle exec fastlane supply --skip_upload_apk true --skip_upload_aab true
```
