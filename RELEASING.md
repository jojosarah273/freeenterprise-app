# Releasing

How releases are cut for this project, and what must never be lost.

---

## Version numbering

Two numbers, deliberately different:

| | |
|---|---|
| **`versionCode`** | Internal, **must only ever increase** — Android uses it to decide whether an install is an upgrade. Managed in `app/build.gradle.kts`. |
| **`versionName`** | The **public** number users see, shown in-app in the FF4 status window. |

The first public release is **`0.1.0`**. Internal development ran from `0.2.0` to
`1.9.0` before any of it was published, so the public numbering deliberately
restarts rather than claiming maturity the app hasn't earned:

| Internal (development) | Public | Notes |
|---|---|---|
| 0.2.0 – 1.8.0 | *unreleased* | 26 iterative builds, kept in `../FE_APK_ARCHIVE/` |
| **1.9.0** | **0.1.0** | first public release (versionCode 30) |

The engine version is tracked **separately** and is unrelated: the app currently
bundles Free Enterprise **4.7.0.Gale**. Never confuse the two when writing notes.

Going forward: bump `versionCode` by 1 and set an honest `versionName`
(`0.1.1` for fixes, `0.2.0` for features) for every release.

## Signing

Release builds are signed with a key you own:

| | |
|---|---|
| Keystore | `~/keystores/freeenterprise-release.jks` (**outside the repo**) |
| Credentials | `keystore.properties` at the project root (**git-ignored**) |
| Key | RSA 4096, PKCS12, alias `freeenterprise`, validity 10,000 days |
| Certificate | `CN=Free Enterprise Android, O=jojosarah273, C=US` |

`app/build.gradle.kts` reads `keystore.properties` if it exists. **If it is
missing, release builds silently fall back to the debug key** — harmless for
local testing, but never ship such an APK. Always confirm the signature (below).

> ### ⚠ Back up the keystore and its password
> Losing `freeenterprise-release.jks` or its password means you can **never
> update an installed copy** of the app again — you would have to publish a new
> package name and ask every user to reinstall. Keep two backups in separate
> places (offline media and an encrypted store), and confirm they restore.

## Release checklist

```bash
# 1. Version: bump versionCode, set versionName in app/build.gradle.kts
#    and add a row to the status log in DESIGN_FF4_UI.md

# 2. Build the release APK
./gradlew clean :app:assembleRelease

# 3. Verify it is signed with YOUR key and is not debuggable
APK=app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs "$APK"
$ANDROID_HOME/build-tools/34.0.0/aapt dump badging "$APK" | grep -E "^package:|debuggable"

# 4. Record the checksum
sha256sum "$APK"

# 5. Tag and push
git add -A && git commit -m "release: vX.Y.Z"
git tag -a vX.Y.Z -m "Free Enterprise Android vX.Y.Z"
git push origin main --follow-tags

# 6. Publish a GitHub Release, attaching the APK and its SHA-256
```

## Release notes template

```markdown
## Free Enterprise Android vX.Y.Z

<one-line summary>

**Engine:** Free Enterprise <engine version, e.g. 4.7.0.Gale>

### Added / Changed / Fixed
- …

### Requirements
- Android 7.0+ (arm64-v8a or armeabi-v7a)
- Your own Final Fantasy II (US) v1.0/v1.1 ROM (validated by MD5, stored
  privately, never uploaded)
- "All files access", so the app can create the shared `FF4_FE` library folder

### Install
Sideload the attached APK. Your seeds, saves and save states live in
`/storage/emulated/0/FF4_FE` **outside** the app, so they survive reinstalling.

### Known limitations
- Doors/entrances randomization is bundled but hidden from the menus
- The checks tracker shows counts per world/area, not per-check locations yet

**SHA-256:** `<checksum>`
```

## What must never be committed

`.gitignore` already covers these — keep it that way:

- `keystore.properties`, `*.jks`, `*.keystore` — signing material
- `local.properties` — machine-specific SDK path
- `build/`, `.gradle/`, `app/build/`, `app/src/main/cpp/{libs,obj}/`
- `*.apk`, `__pycache__/`, `*.pyc`

## Pre-public checklist

- [x] Artwork licensing settled — cursor sprite MIT, font CC0 (`THIRD_PARTY_NOTICES.md`)
- [x] Project licence in place — MIT (`LICENSE`)
- [x] No ROM, no `.env`, no credentials in the tree
- [ ] On-device pass over the release APK (Regenerate, classic UI, tracker, playback)
- [ ] `README.md` screenshots, if wanted
