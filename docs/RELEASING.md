# Releasing VeloSpot (reproducible F-Droid builds)

VeloSpot is built so that **F-Droid can reproduce the exact APK** you publish on
GitHub Releases — byte-for-byte identical except for the signing block. This page
explains how to build reproducibly, manage the signing key, and cut a release.

> **Why reproducibility matters:** F-Droid rebuilds the app from this repository's
> tagged source and compares the result against your signed release APK. If they
> match, F-Droid ships *your* signed binary. This requires that **no step depends
> on anything outside the pinned source** — which is why BRouter is compiled from
> a pinned git submodule and there are no prebuild scripts or binary blobs.

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
`v1.7.9`). Every checkout — yours, CI and F-Droid — therefore compiles the same
source. To bump BRouter later:

```bash
cd brouter-upstream
git fetch --tags
git checkout v1.7.10        # or the desired tag/commit
cd ..
git add brouter-upstream
git commit -m "chore: bump BRouter to v1.7.10"
```

---

## 2. Building reproducibly (local)

Use the **same JDK that F-Droid uses (JDK 17, Temurin)** and the project's pinned
Gradle wrapper (do not run with a system Gradle):

```bash
# JDK 17 must be active (java -version → 17)
./gradlew :app:assembleFdroidRelease
```

No preparation scripts are needed — a plain Gradle invocation resolves BRouter
from the `:brouter` source module. The unsigned/locally-signed APK is written to:

```
app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

Determinism is enforced by:

- **BRouter from pinned source** (`:brouter` module + submodule commit).
- **`dependenciesInfo { includeInApk = false }`** — strips the AGP-generated,
  Google-signed "dependency metadata" block (opaque/non-reproducible) from the APK.
- **Pinned toolchain** — fixed Gradle wrapper (`gradle-wrapper.properties`), AGP
  and Kotlin versions (`gradle/libs.versions.toml`), and JDK 17.
- **Reproducible archives** in the `:brouter` module (no timestamps, stable order).

---

## 3. Signing key management

### Where credentials come from

The release `signingConfig` reads credentials in this order:

1. **`keystore.properties`** at the repo root (for local releases) — *gitignored*.
2. **Environment variables** (CI) — `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD`.

If neither is present, the build falls back to the debug signing config (so
ordinary local builds still work).

`keystore.properties` (never commit this):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=********
keyAlias=velospot
keyPassword=********
```

`keystore.properties`, `*.jks`, `*.keystore`, `*.p12` and `*.base64` are all listed
in `.gitignore`.

### Creating a keystore (one-time)

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias velospot \
  -keyalg RSA -keysize 4096 -validity 10000
```

### ⚠️ Back up the keystore — losing it is fatal

Store `release.jks` **and** its passwords in a secure, offline backup (password
manager + encrypted offsite copy). **If you lose the key:**

- You can no longer publish updates that F-Droid can reproducibly verify against
  your previous releases.
- The `AllowedAPKSigningKeys` fingerprint in `fdroiddata` would have to change,
  which is a disruptive, manual process.

Treat the keystore as the single most important secret of the project.

---

## 4. Determining the signing certificate SHA-256 fingerprint

F-Droid pins your certificate via `AllowedAPKSigningKeys` in `fdroiddata`. Get the
SHA-256 fingerprint with either of:

```bash
# From a built & signed APK:
apksigner verify --print-certs VeloSpot-v1.0.13.apk

# Or directly from the keystore:
keytool -list -v -keystore release.jks -alias velospot
```

Use the **SHA-256** value (lowercase, colons removed) as `AllowedAPKSigningKeys`.

---

## 5. Cutting a release (GitHub Actions)

Releases are automated by `.github/workflows/release.yml`:

1. Make sure `main` is green and the changelog `## [Unreleased]` section is filled.
2. Push a tag `vX.Y.Z`:

   ```bash
   git tag v1.0.14
   git push origin v1.0.14
   ```

3. The workflow then:
   - checks out the repo **with submodules**,
   - sets the static `versionCode` / `versionName` literals to match the tag,
   - runs `assembleFdroidRelease` (+ `assembleGooglePlayRelease`),
   - signs with the keystore decoded from `KEYSTORE_BASE64`,
   - uploads the F-Droid APK as **`VeloSpot-vX.Y.Z.apk`** (the exact name the
     fdroiddata `Binaries` URL expects:
     `https://github.com/drzeeb/VeloSpot/releases/download/v%v/VeloSpot-v%v.apk`).

### Required GitHub Actions secrets

Set these once under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | signing key alias |
| `KEY_PASSWORD` | key password |

Generate `KEYSTORE_BASE64` (one-time):

```bash
# macOS / Linux
base64 -i release.jks | tr -d '\n'

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```

---

## 6. fdroiddata recipe (reference)

The custom `prebuild` step and the `BRouter` `srclib` are **no longer needed**. The
recipe in `fdroiddata` simplifies to a standard Gradle build of the `fdroid`
flavor, with submodules enabled and the binary verified against your release:

```yaml
Builds:
  - versionName: 1.0.13
    versionCode: 10013
    commit: v1.0.13
    subdir: app
    submodules: true
    gradle:
      - fdroid

AllowedAPKSigningKeys: <SHA-256 fingerprint from step 4>

Binaries: https://github.com/drzeeb/VeloSpot/releases/download/v%v/VeloSpot-v%v.apk
```

