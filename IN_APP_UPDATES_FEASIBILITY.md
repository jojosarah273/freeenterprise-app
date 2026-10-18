# Feasibility — in-app updates (engine and app)

**Assessment only — no code changed.** Can the app offer "Check for updates" and
update itself, without the user reinstalling?

**Verdict: yes for engine/content updates (no install prompt at all), and yes for
full-app updates with one system confirmation.** There is no silent binary
self-replacement on Android — but there *is* a route that never touches the APK,
because our randomizer is interpreted Python and data, not compiled code.

---

## 1. The hard constraint, stated plainly

Android forbids an app from replacing its own APK in place. No permission,
API or trick changes that; the only exceptions are Play (Google-signed delivery),
root, or device-owner provisioning. Every sideloaded updater — F-Droid,
Obtainium, and the app itself — does the same thing: download the APK, then ask
the **system installer** to apply it.

That said, "reinstall" is not what happens. A new APK with the **same package
name, same signing key and a higher `versionCode`** is an *update*: Android keeps
all app data, and the user taps **Update** instead of **Install**. Our
`FF4_FE` folder and app-private storage are untouched.

## 2. Why engine-only updates are the interesting option

Our randomizer is **CPython plus data files**, extracted at runtime and loaded
from `filesDir` — not compiled into the binary. So it can be replaced on disk
while the app stays exactly as installed. That is the same shape as
[**bryanthaboi/gen1recomp**](https://github.com/bryanthaboi/gen1recomp)
(≈4k ★), which the request referenced; see §3.

| | Engine-only update | Full APK update |
|---|---|---|
| Payload (measured on this project) | **3.9 MB** zipped | **26.2 MB** |
| User action | **none** — takes effect immediately | one *Update* tap in the system installer |
| New permissions | `INTERNET` only | `INTERNET` + `REQUEST_INSTALL_PACKAGES` |
| Can change | engine, flag spec, presets, checks DB | everything, incl. Kotlin/native/UI |
| Reinstall prompt | never | never (it is an update, not an install) |

Measured payloads:

```
python_ext + spec + presets, zipped   = 3.9 MB      (13 MB uncompressed)
release APK                           = 26.2 MB
```

## 3. Prior art — how gen1recomp does it

Worth copying, because it solves the same problem and has clearly been through
the wars. [`bryanthaboi/gen1recomp`](https://github.com/bryanthaboi/gen1recomp)
(≈4k ★) is a Lua/LÖVE2D recreation of Gen 1–3. The platform binary ships per
platform (APK / IPA / AppImage / flatpak / macOS) while **content and engine logic
update in place** through a separate index repo,
[`gen1recomp-mod-index`](https://github.com/bryanthaboi/gen1recomp-mod-index).

The pipeline, as it actually exists:

| Piece | Detail |
|---|---|
| **Published index** | `data/index.json`, built by `scripts/build-index.mjs` and served from **GitHub Pages**, regenerated on every push (`.github/workflows/pages.yml`) |
| **Schemas** | `schema/mod.schema.json` + `schema/cart.schema.json` — JSON Schema (draft 2020-12); index entries mirror the *engine's own* `manifest.json` fields |
| **Entry shape** | `id`, `title`, `author`, `version` (semver), `categories`, `repo`, plus index-only fields so "the site and launcher" can find and update it. The payload is an installable **`.zip`**, addressed either by a direct `downloadURL` or a pinned `github: {repo, version, sha256}` |
| **Submission + CI** | mods live as `mods/<Author>@<id>/meta.json`; `validate.yml` enforces the schema, with `check-blocklist.mjs`, `check-duplicates.mjs`, `check-links.mjs` and `gate-releases.mjs` gating the PR |
| **Moderation** | a Docker-based `scanner/` produces scan reports; `blocklist.json` lists banned authors *with reasons*, matched case-insensitively against the author and repo owner |
| **Rate limits** | an `oauth-worker/` (Cloudflare Worker) fronts GitHub API usage |
| **In-app** | a mod manager (F10) installs and updates zips; the platform binary only moves for platform-level work |

**Lessons we take:** a *static* manifest beats an API (no auth, no rate limits,
cacheable); checksums belong in the index; version the schema from day one; and
the content/engine vs platform-binary split is exactly the one we want.

**A warning worth heeding:** their blocklist exists partly because an author
"published a mod-sandbox escape" — third-party content is a real attack surface
and drags in an entire moderation apparatus. Our engine updates should therefore
be **first-party only and signed** (§5.5). If we ever accept community engines we
inherit their scanner/CI/blocklist problem wholesale.

## 4. What I verified on this project

| Fact | Finding |
|---|---|
| Network posture today | **no `INTERNET` permission** — updates would be the app's first network use |
| Permissions now | only `MANAGE_EXTERNAL_STORAGE` (+ legacy `WRITE_EXTERNAL_STORAGE` ≤ 29) |
| minSdk / targetSdk | 24 / 34 — `PackageInstaller` session API (21+) fully available |
| Signing | release key exists and is consistent — the hard prerequisite for in-place updates |
| Data locations | `filesDir` (engine, cache) + external `FF4_FE` — neither is touched by an update |
| Engine re-extraction | already **version-stamped** (`SeedEngine`, since 1.5.3) and re-extracts when `versionCode` changes |
| Engine version per seed | `SeedMeta.version` already records it (e.g. `4.7.0.Gale`), so sessions can be labelled by the engine that made them |
| GitHub asset digests | the Releases API returns `digest: sha256:…` per asset — checksums need no extra manifest |
| Private repo | unauthenticated API calls **404**, so an in-app checker cannot read it while private |

## 5. Route A — engine-only updates (recommended first)

### 5.1 Updatable scope

Everything interpreted or data-driven:

- `python_ext/` — `FreeEnt` (randomizer), `f4c` (compiler), `pyaes`
- `spec/ui_spec.json` (flag editor surface), `spec/checks.json` (403 checks)
- `presets/presets.json`

**Not** updatable this way: Kotlin code, the FF4 UI, native libraries, the
Snes9x core, the manifest. Those need Route B.

### 5.2 Manifest

A static JSON, versioned like gen1recomp's schemas:

```json
{
  "schema": 1,
  "generated": "2026-10-18T00:00:00Z",
  "engine": {
    "version": "4.7.1.Gale",
    "url": "https://…/freeenterprise-engine-4.7.1.Gale.zip",
    "sha256": "…",
    "sizeBytes": 4100000,
    "minAppVersionCode": 34,
    "notes": "…"
  },
  "app": {
    "versionCode": 35,
    "versionName": "0.1.5",
    "url": "https://…/freeenterprise-app-0.1.5.apk",
    "sha256": "…",
    "sizeBytes": 27450000,
    "notes": "…"
  }
}
```

The zip carries its own `engine.json` (engine version + upstream commit it was
built from), so a swapped-in engine is self-describing.

### 5.3 Update flow

```
Settings -> Check for updates
   fetch manifest (cache-busting query, ~1 KB)
   compare manifest.engine.version with the installed engine version
     -> "Engine 4.7.1.Gale available (4.0 MB)"   [Update]
   download to cache/ with progress  (reuse FF4ProgressOverlay)
   verify sha256  (and reject a downgrade / minAppVersionCode mismatch)
   unzip to cache/engine-staging/
   smoke test: import the bridge, canonicalise a known flag string,
               compare against the expected canonical output
   swap: python_ext -> python_ext.prev ; engine-staging -> python_ext
   stamp the new engine version; keep python_ext.prev for Rollback
   refresh the running interpreter (see 5.4)
   "Engine updated to 4.7.1.Gale — no reinstall needed"
```

### 5.4 The one real technical wrinkle: making the running interpreter notice

Chaquopy starts CPython once per process and caches imported modules, so new
files on disk are invisible until the module cache is cleared. On update we must:

1. `for name in list(sys.modules): if name.split('.')[0] in ('FreeEnt', 'f4c'): del sys.modules[name]`
2. delete every `__pycache__` inside the new tree (the old ones too — they are
   written at runtime because `filesDir` is writable)
3. re-import `fe_bridge`'s engine lazily on next use

`flagspec.pickle` and the CSV databases are opened at **import** time, so
re-importing is what picks up the new spec — no separate step needed.

Do this **only when no generation is running** (the existing engine lock/state
can guard it). If the refresh ever fails, fall back to prompting a restart —
still no reinstall, just a relaunch — and roll back to `python_ext.prev`.

### 5.5 Safety

| Concern | Handling |
|---|---|
| Corrupt/hostile download | SHA-256 from the manifest; refuse on mismatch. Optionally require an Ed25519 signature over the zip with a public key baked into the app |
| Downgrade / incompatible engine | refuse if `version` is older or `minAppVersionCode` exceeds `BuildConfig.VERSION_CODE` |
| Bad engine bricks the app | smoke test before the swap; keep `python_ext.prev`; one-tap Rollback |
| Half-written swap | stage in `cache/engine-staging/`, swap by directory rename |
| Update mid-generation | block the Update action while generating |

### 5.6 UX sketch (FF4 style)

`Settings` gains a `Check for updates` entry showing the installed engine version.
If an update exists it opens a window with the new version, size, notes, and
`Update` / `Cancel`; downloading reuses the modal progress window, then reports
success. A `Rollback engine` entry appears once a previous engine exists.

The entry point is `Settings`, which lives in the app's menu stack. The player is
a **separate activity** (`FF4PlayerActivity`) whose pause menu carries no update
action, so an update **cannot be started while playing** — the app is already
"not in-game" by the time you can reach the button. Only blocking during an
in-flight *generation* matters (the engine files are being read).

## 6. Route B — full APK self-update

Same manifest, but for the `app` block. Needs:

1. `REQUEST_INSTALL_PACKAGES` in the manifest, plus `INTERNET`.
2. A **one-time** "allow installs from this app" toggle
   (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`) — if it is missing the install silently
   fails, so the flow must detect and deep-link it with clear copy.
3. Download the APK → verify SHA-256 → **also compare the downloaded APK's
   signer certificate** with our own (`getPackageArchiveInfo`) as defence in depth
   (the OS enforces signature match for updates anyway).
4. Hand it to `PackageInstaller` (session + `commit`), which shows Android's own
   **Update** dialog. `ACTION_INSTALL_PACKAGE` is deprecated; sessions are the
   supported path.
5. **Before committing**: nothing is in flight — the installer is only reachable
   from `Settings`, so by construction the player is not running and no
   generation is underway. A `.srm` flush first is cheap belt-and-braces.
6. The process is killed during install. On relaunch everything is intact, and
   our `versionCode`-stamped engine re-extract refreshes the bundled engine
   automatically — a neat synergy with the 1.5.3 change.

Implementation notes: a manual, user-initiated "Check for updates" only — never
automatic — is the right posture for an app whose identity is "no network".
`FF4ProgressOverlay` already covers download progress.

## 7. Routes we are not taking

| Route | Why not |
|---|---|
| **Play Store in-app updates** (`AppUpdateManager`) | the only genuinely seamless path, but **blocked**: Play effectively bans `MANAGE_EXTERNAL_STORAGE`, so `FF4_FE` at the root of shared storage would have to move to app-specific storage or SAF. "Seamless updates" and "files at `/storage/emulated/0/FF4_FE`" are mutually exclusive. |
| **Shizuku / ADB / root** | silent installs, but requires the user to set up privileged tooling. Not appropriate for a general release. |
| **Embedding a GitHub token** | extractable from the APK. Never. |
| **Delta/bsdiff patches** | no OS support on this path; we would be hand-rolling patch application for modest gain over 3.9 MB. |

**Delegating to another app** remains a zero-code fallback: F-Droid, or
**Obtainium**, which watches GitHub releases and updates the app for you. Worth
documenting for users who prefer it.

## 8. The private-repo problem

An in-app checker cannot read a private repo unauthenticated (verified: 404).
Options:

1. **Go public** when ready — then the GitHub API works with no auth and no
   manifest at all. Simplest.
2. **A public static manifest** (GitHub Pages, a gist, or your own host) —
   works *while the code stays private*. This is what gen1recomp does, and it
   also dodges API rate limits. **Recommended if we implement before publishing.**
3. A signed manifest on your own domain, if you want no dependency on GitHub.

The manifest only ever exposes a version string, a URL and a checksum — no code,
no secrets.

## 9. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Unknown-sources not granted (Route B) | install does nothing | detect and deep-link; explain once |
| OEM installer quirks (Xiaomi/Samsung/Huawei) | prompt differs or is blocked | test on the real device early; fall back to "open the APK yourself" via FileProvider |
| Signing key lost/changed | updates refuse to install | already documented in `RELEASING.md` |
| `versionCode` not increased | update refused | already enforced by the release checklist |
| Engine update changes seeds | a "same seed" regenerates differently | `SeedMeta.version` records the engine; label sessions and warn on Regenerate |
| Added networking breaks an offline app | new failure modes, user distrust | manual checks only; `INTERNET` the only added permission; a setting to disable |
| Play Protect warning on sideload | user scare | explain in release notes/FAQ |
| 26 MB per app update | mobile data | engine-only for engine changes; optionally publish **per-ABI APKs** (arm64-only drops ~5 MB) and choose via `Build.SUPPORTED_ABIS` |
| Update while playing | **not reachable**: the update button lives in `Settings`, and the player is a separate activity with no update action | only becomes a concern if we later add a background/automatic update path |

## 10. Phased plan

> **Deferred by decision.** We are not implementing this yet; it comes off the
> shelf once the repository is public, so that no public manifest has to be
> maintained while the code is still private. The plan below is what we pick up
> then.

- **Phase 1 — engine-only updates.** Static manifest, verified zip download,
  staging + smoke test + swap, interpreter refresh, rollback, `Check for updates`
  and `Rollback engine` in Settings. Adds only `INTERNET`.
- **Phase 2 — full APK self-update.** `PackageInstaller` path, unknown-sources
  guidance, signature check, pre-install save.
- **Phase 3 — niceties.** Optional per-ABI APKs, automatic check *opt-in*,
  release-notes viewer, and the same engine model reused for a future iOS build
  (content/engine updatable in place; the binary via the App Store).

**Effort:** Phase 1 ≈ a day; Phase 2 ≈ 1–2 days including device testing.

## 11. Decisions needed when we pick this up

1. **Engine-only first, or both phases together?** (Phase 1 delivers most of the
   value with none of the installer friction.)
2. **Manifest now, or wait until the repo is public?** (decides whether updates
   work before you flip visibility)
3. **Accept the `INTERNET` permission?** This is the one real change to the app's
   offline identity — worth an explicit yes.
4. **Wrap the engine zip in a signature** (Ed25519 with a baked-in public key), or
   rely on HTTPS + SHA-256 from the manifest?
5. **Manual "Check for updates" only, or also an optional check on launch?**
