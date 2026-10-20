# Menu portability: Android, Windows, Linux (and iOS)

Status: **plan, nothing implemented.** Written after the multi-device sync research,
while the app is at 0.1.25.

The question this answers: what would it take to move the FF4 menu interface into a
form that runs on Android, Windows, Linux and potentially iOS, from one codebase,
so Free Enterprise is a *game* on the desktop rather than a web UI with a ROM
attached.

The emulator core is not part of the problem — the whole Snes9x 1.63 source and
the libretro glue are vendored in `app/src/main/cpp` and already compile from
CMake, so they build for any target. The engine is not part of the problem
either: it is pure Python (stdlib plus `pyaes`), and it already runs on this
machine. **The problem is the shell — specifically the menus.**

## 1. What "portable menus" has to mean

The point of the app is the experience: FF4 windows, controller-first navigation,
seed library, flag editor, tracker, progress overlays. On the desktop, a user has
the randomizer website one tab away, so a desktop build has to be the same
*experience*, not a functional equivalent. That rules out "reimplement the menus
so they look similar" — two implementations drift, and we have already seen how
much trouble a single hardcoded heading or a 6dp inset can cause.

So the target is: **one implementation of the menu system, rendered by thin
per-platform backends.**

## 2. Current state, measured

| Layer | Lines | Portability today |
| --- | --- | --- |
| Snes9x core + libretro glue | 95,804 | portable as-is (source in tree, CMake) |
| FE engine (`FreeEnt`, `f4c`, `pyaes`) | 34,672 | portable as-is, pure Python |
| `ff4emu.cpp` (JNI bridge) | 519 | portable logic, only ~4 Android references |
| `fe_bridge.py` | 514 | JSON in / JSON out — a process contract already |
| Kotlin app + activities | 6,210 | half real logic, half Android plumbing |
| `ff4ui/` (the menu system) | 984 | **entirely Android View code** |

The nine files that have to move:

| File | Lines | What it really is | Where it goes |
| --- | --- | --- | --- |
| `FF4Window.kt` | 78 | window chrome: body, border, inner bevel | draw-command emitter |
| `FF4Menu.kt` | 223 | list model, cursor, scrolling, selection | pure menu model |
| `FF4Dialog.kt` | 113 | message + options window | pure dialog model |
| `FF4ProgressBar.kt` | 70 | bar primitive | draw primitive |
| `FF4ProgressOverlay.kt` | 129 | modal progress window | pure overlay model |
| `FF4Theme.kt` | 59 | palette + metrics + font loading | pure tokens + atlas metrics |
| `GameIcons.kt` | 63 | sprite table | sprite atlas entry |
| `FF4Activity.kt` | 153 | immersive mode, insets, edge buffer | platform app shell |
| `FF4MenuActivity.kt` | 96 | wires layout to menu, status/hint boxes | platform app shell |

None of it is conceptually Android-specific. It is rectangles, a pixel font, a
cursor, and a list of items — which is why this is a refactor rather than a
rewrite.

## 3. The design decision: menus as data

**The menu system should emit a draw list, and the platform should rasterise it.**

```
menu model  ──►  List<DrawOp>  ──►  renderer (Android Canvas | desktop GL | iOS Metal)
                    rect(text, colour, border, bevel)
                    text(glyph run, x, y, colour)
                    sprite(index, x, y)
                    clip(rect)
```

Why this and not a cross-platform UI toolkit:

- **Geometry parity is the whole point.** Our fixed-status / fixed-hint layout
  exists to stop boxes resizing; that only holds if text metrics are identical
  everywhere. If we use platform text stacks, they will not be.
- **Own the font.** Bake `ff4.ttf` into a bitmap atlas with our own advance and
  line-height table. Then a box's height is computed from the same numbers on
  every platform, and the "reshaping" class of bug becomes impossible rather than
  fixed.
- **It is testable.** A draw list can be asserted in a JVM unit test, and rendered
  to a PNG for golden-image comparison with no device and no emulator. That
  finally gives the menu system a regression net; today the only way to check a
  menu is to look at it.
- **No toolkit tax.** No Skia, no Compose, no JVM UI runtime, no per-platform
  theme surprise. The FF4 look is a handful of primitives.

The model keeps all the behaviour we have already tuned: focus rules, selection
memory per layer, key repeat, scrolling, dimmed/unfocused window states, the
status and hint slots, the progress overlay's modal input swallowing, and the
tracker's page composition.

## 4. The platform seam

Seven small interfaces, each implemented once per platform:

| Interface | Responsibility | Android | Desktop | iOS (later) |
| --- | --- | --- | --- | --- |
| `Video` | present a frame (game) and a draw list (UI) | SurfaceView + Canvas | GLFW/SDL window + OpenGL | Metal / CoreAnimation |
| `Audio` | queue PCM from the core | AAudio / OpenSL | SDL audio | CoreAudio |
| `Input` | digital + analog, hotplug | KeyEvent + controller | SDL game controller DB | GCController |
| `Storage` | library folder, base ROM, save states | shared storage + SAF | app data dir + file dialog | sandbox + document picker |
| `Clock` | frame pacing, audio-clock master | SystemClock | SDL/GLFW timing | mach time |
| `Engine` | generate a seed, read version, update | Chaquopy in-process | bundled CPython child process | embedded CPython |
| `Shell` | fullscreen, insets, safe areas, updates | immersive + insets + APK | borderless fullscreen; file swap | none (no self-update) |

The `Engine` difference is deliberate and worth keeping: one contract, two hosts.
On Android the engine is in-process because Chaquopy makes that easy; on desktop a
child process is simpler *and* makes engine updates a directory swap. Both speak
the JSON contract that `fe_bridge.py` already exposes.

`Input` deserves one shared table: the current button map (D-pad, A/B/X/Y, Start,
Select, L1/R1 with L+R flee, L2 tracker, R2 fast-forward) should live in the
portable layer, with each platform only translating its native events into that
vocabulary. That is what makes controller integration *identical* rather than
merely similar.

## 5. Steps

Ordered so that every step ships, is reversible, and pays for itself even if the
desktop client never happens.

**Step 0 — gates (1–2 days).** Extend `scripts/check_invariants.sh` and add a
`scripts/selftest.sh` that runs it plus unit tests plus the host engine checks.
No product change; this is the net everything below falls into.

**Step 1 — extract pure logic (about a week).** Move the decoders out of Android
classes behind a `Storage` interface: `Ff4Save`, `CheckDb`, `Spoiler`,
`SeedMeta`/`RomLibrary`, `Presets`, `FlagSession`, `FlagsetLibrary`, `UiSpec`,
`EditorPrefs`. Zero `android.*` imports in the result. Add unit tests for each
offset, bit and name mapping. This is the single highest-value step: it makes the
tracker and metadata testable today, and it is the same code iOS and desktop
would need.

**Step 2 — pure menu model + font atlas (1–2 weeks).** Split `ff4ui` into a
framework-free model that emits draw ops, and keep the existing Android renderer
as the first backend. Bake the glyph atlas and metrics. Add draw-list assertions
and golden images. Android must look *byte-identical* after this step — it is a
pure refactor, and any pixel change is a bug in the step.

**Step 3 — put Android behind the seam (about a week).** Introduce the seven
interfaces and re-point the existing app at them. Still one platform, still no
behaviour change. This proves the abstraction with the shipping product before a
second target exists — much cheaper than discovering the seam is wrong later.

**Step 4 — desktop shell (1–2 weeks).** Window, borderless fullscreen, GL present
of the core's framebuffer, SDL audio, SDL gamepad, file dialogs, app data paths.
Reuse the draw-list renderer against OpenGL. Choose the host language here:

- *JVM shell* (Kotlin, LWJGL/SDL bindings): no new language, the model is linked
  directly, fastest path, ~100MB packaged with a JRE.
- *Native shell* (C++/SDL2 + Kotlin/Native static lib, or logic moved to Python):
  smallest and most game-like, best for handhelds and Steam Deck later, more
  build work.

Recommendation: JVM first for speed, revisit native only if size or startup
actually hurts.

**Step 5 — engine on desktop (3–5 days).** Bundle a relocatable CPython (or use
the system one), run `fe_bridge` as a child process over the existing JSON
contract, and reuse the same engine-bundle update path. Because the engine is
interpreted Python with no binaries, **one engine artifact serves every
platform**, which is an unexpected win.

**Step 6 — packaging, first run, game feel (about a week).** AppImage and a
portable `.tar.gz` for Linux, a zip plus installer for Windows, controller-first
first-run, save-state and library locations, version string in the hint box, and
the self-update path (file swap for desktop — no store rules apply).

**Step 7 — one release pipeline (2–3 days).** One tag publishes: Android APK,
Linux build, Windows build, and the universal engine bundle, with the invariant
and self-test gates applied to all of them. Dev builds for all three platforms
stay local; only verified builds reach `main` and tagged releases.

**Step 8 — features (ongoing).** Golbez (a second engine), richer tracker data
(the completion time, `Stats_EverCompleted`, `Tracker__KeyItemLocations`), and
online work all land once in the portable layer and appear on every platform.

Rough total: **6–9 weeks part-time**, with a usable Linux client around the
half-way mark.

## 6. iOS: same menus, different shell

The menu work above is the same on iOS, because the model is portable. What
differs is the shell, and the constraints are now less about review and more about
how you intend to distribute:

- The engine runs **in-process** only — no subprocess on iOS. Embedded CPython
  (Python-Apple-support) works, and the generation path is stdlib-only, which I
  verified: `PIL` and `subprocess` appear only in dev tooling.
- The core compiles for arm64 with the same CMake, interpreter only (no JIT).
- **No in-app updates of any kind.** That applies to sideloaded builds too, since
  it is a code-signing rule, not a store rule. So on iOS the engine bundle
  channel simply does not exist; everything must ship in the app bundle.
- Storage is the sandbox plus the Files app and a document picker; there is no
  shared `FF4_FE` folder.
- For your purposes — free developer account and unofficial IPA / LiveContainer
  distribution — the relevant costs are the 7-day provisioning window for a free
  account, no background execution, and no self-update. Menu portability is
  already paid for by the steps above, so the incremental work is the shell
  (Swift app, Metal or CoreGraphics rasteriser of the same draw list, GCController
  input) plus the storage redesign.

Decision needed later, not now: whether the shared model is linked via Kotlin
Multiplatform/Native, or re-implemented in Swift against the documented draw-op
contract. Both are viable; KMP avoids two copies of the menu logic.

## 7. Risks and how the steps retire them

| Risk | Retired by |
| --- | --- |
| Menu geometry drifts per platform | Step 2 — own the glyph atlas and metrics; golden images |
| Menu bugs only findable by eye | Step 2 — draw-list assertions and golden tests |
| The seam is wrong | Step 3 — one platform proves it before a second exists |
| Frame pacing / audio latency on desktop | Step 4 — audio clock as master, emulation on its own thread, UI consumes the latest frame |
| Controller differences across dozens of pads | SDL's controller database on desktop; one shared button vocabulary |
| Engine hosting differences | Keep the JSON contract; two hosts, one behaviour |
| Three-target dev loop | Step 0/7 — one self-test gate, dev builds stay local |
| Packaging size / cold start on desktop | Measure in step 6; native shell remains the fallback |
| Scope creep into a UI toolkit | Explicitly rejected in section 3 |

## 8. What not to do

- Do not port the emulator core again; it already builds everywhere.
- Do not rewrite the engine; it already runs everywhere.
- Do not maintain two menu implementations, and do not accept "looks the same".
- Do not let a UI toolkit own our text metrics or our layout arithmetic.
- Do not let UI frame rate gate emulation speed.
- Do not make in-place updating load-bearing: the app must be fully usable with
  the engine it ships with, so every platform — including iOS — stays viable.

## Progress

**Step 2 has begun (dev channel).** A `:core` module now exists with no Android
dependency at all, enforced by an invariant check, and holds the first pieces of
the interface:

| In the core | What it is |
| --- | --- |
| `Ff4Palette` | the ten colours, values matching the shipped theme exactly |
| `Ff4Scale` | the layout unit (short edge / 224, min 2px) and everything derived from it |
| `Ff4Geometry` | rects and strokes, with the stroke-band arithmetic the border depends on |
| `Ff4TextMetrics` | the platform seam for measuring text, the one thing that keeps geometry identical |
| `Ff4WindowChrome` | the window body, border and bevel, as numbers instead of draw calls |

The chrome calculation was moved out of `FF4Window` with a test that re-derives the
arithmetic the view used to perform inline, across 25 combinations of screen size
and window size, so the refactor cannot have moved a coordinate. `FF4Window` now
performs the same three Canvas calls as before, driven by those numbers, and
`FF4Theme` delegates its measurements to the core and keeps only the font.

`./scripts/selftest.sh` is the gate for this work: the structural invariants plus
the core's unit tests (15 so far).

Since then, in the same step:

| In the core | Switched on for Android now? |
| --- | --- |
| `Ff4MenuModel` | yes: menu navigation (wrap, skip disabled, remembered row) |
| `Ff4ProgressBarChrome` | yes: the generation and update progress bar |
| `Ff4WindowChrome` | yes: every window |
| `Ff4Scale`, `Ff4Palette` | yes: the layout unit and the colours |

Both new geometry models ship with a parity test that re-derives the arithmetic the
view performed inline, so switching them over could not move a pixel. That test
earned its keep immediately: it caught a genuine one-bit float difference between
`(width - border) - border` and `width - border * 2` in the progress bar, which was
enough to move a rounded pixel.

A test build is produced by `:app:assembleDebug`. It installs *beside* the public
app (`applicationIdSuffix = ".dev"`, `versionNameSuffix = "-dev"`, debug
signature), so the released app is never replaced and both can be compared on one
device. Note that the base ROM lives in app-private storage, so a dev build needs
its own import; the seed library, flagsets and saves live in `FF4_FE` on shared
storage and are shared.

**Step 4 has begun: there is a runnable Linux window.** `:desktop` draws the
interface through a software canvas built from the same core, with a headless PNG
export from that same canvas, so what the window shows can be inspected and
measured rather than described. Verified by running it on a virtual display and
measuring the rendered pixels: the selected menu row comes out at 53 px, exactly
`padding(6) + cursor(41) + padding(6)`.

Two bugs it caught, both mine: the Gradle asset copy applied one `into` to every
source, so the font landed at `/ff4/ff4.ttf` and the app could not find it; and the
progress overlay computed its height with different arithmetic than it placed its
content with, so the elapsed line was drawn over the bottom border. The second is
now impossible by construction, and both were found by measuring pixels rather than
looking at them — my eye misread two other things as defects that measurement
showed were correct (a right-aligned value 22 px clear of the border, and status
text 12 px clear).

**Portability finding: the game font has 99 code points.** It contains ASCII and
little else — no middle dot, no tick, no arrows, no em dash. Android hides this by
silently substituting glyphs from a system font, so a system-typeface tick appears
mid-sentence in FF4 text. A single-font renderer has no fallback and draws a
missing-glyph box, so the desktop substitutes characters the font does have, and
measurement and drawing share that substitution so the layout cannot drift. Making
the two platforms agree properly is a deliberate change to the Android appearance
(a core-level mapping, or fallback glyphs in the atlas) and has not been made.

Still to do for step 2: the menu row geometry (it belongs with the glyph atlas,
since exact row heights depend on exact text metrics), the dialog and progress
overlays, and the glyph atlas itself, which is the change that actually makes text
identical rather than merely proportioned the same.

## 9. Open decisions

1. Desktop host: JVM shell first, or native shell from the start.
2. Whether the portable model is a separate Gradle module (`:core`) consumed by
   `:androidApp` and `:desktopApp`, or one module with source sets. Recommendation:
   separate modules, so the *absence* of Android imports is structurally enforced.
3. Whether step 1 (pure logic) and step 2 (menu model) happen now, before the
   public release settles, or after. Recommendation: now — both are refactors
   with tests as the deliverable, and every later feature benefits.
4. iOS timing — after the desktop client proves the seam, or in parallel.
