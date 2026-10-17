# WEB UI BREAKDOWN — the ff4fe.galeswift.com/make flag editor

Complete function-by-function anatomy of the website's flag editor, and how we
reproduce each piece in the offline native app. The goal: **full parity of flag
editing so we can fine-tune our own experience and build custom flagsets.**

---

## 1. Source inventory (what the website is made of)

| File | Role | ~lines |
|---|---|---|
| `server/uispec.txt` | the flag-spec source, an indented DSL | 740 |
| `server/compile_uispec.py` | compiles uispec.txt → `script/uispec.js` | 161 |
| `server/script/uispec.js` | generated data: `var FLAG_UISPEC = [...]` (pure JSON payload) | 6,870 |
| `server/uispec_flagdescriptions.txt` | long-form descriptions for flags | — |
| `server/script/flags.js` | JS flag logic: FlagSetCore + FlagLogicCore | 11,508 |
| `FreeEnt/flagsetcore.py` + `flagspec.pickle` | **the Python twin of flags.js** (already on-device) | — |
| `server/script/make_react.js` | React UI components (the editor app) | 827 |
| `server/presets.json` | preset library (already native in our app) | 209 |
| `server/template/make_react.html` | shell: metaconfig toggles, seed input, generate() | — |

> Big win: `flags.js` and `flagsetcore.py` are the same algorithm in two
> languages. We already bundle the Python one, so **validation, canonicalization
> and dependency logic need ZERO porting** — we call it through the engine.

## 2. The spec DSL (`uispec.txt`) → data model

Line grammar (after `== SECTION TITLE` headers and `.`-depth indentation):

```
[flag]      → toggle ("check"): independent set/unset
(flag)      → radio: mutual-exclusion *input* kind (editor treats like toggle; exclusivity enforced by FlagLogic messages)
<flag>      → dropdown group: consecutive <..> rows become one selector's options
()  []  <>  → empty widget = anonymous container flag (@anonN)
@-prefixed  → "container" (non-flag UI node; its descendants toggle it on automatically)
* suffix    → expand to all matching flags from flagspec order (e.g. O1:* objectives)
suffix !    → hard (required-style, red styling)
suffix #    → compact (subcontrols rendered compact)
suffix -    → null  (a real flag but never shows a ✓)
suffix *    → important (selector omits its "(none/clear)" option)
ECHO:flag   → splice another control's subcontrols here
---         → separator row (from blank line handling / ---- line)
```

Compiled shape (this is what we bundle as `ui_spec.json`):

```json
[ { "title": "OBJECTIVES",
    "controls": [
      { "flag": "@O",            "title": "Alternate objectives" },
      { "flag": "Omode:classicforge", "title": "Classic Forge-the-Crystal" },
      { "flag": "Obosscollector:1",   "title": "Collect 1 boss", "subcontrols": [...] },
      { "flag": "O1:*custom…",   "title": "...", "description": "<p>…</p>" },
      …
      { "flag": "…", "type": "select", "subcontrols": [options…] }
] } ]
```

Extra keys: `description` (HTML), `hard`, `compact`, `null`, `important`,
`type` (`"select"` or `"separator"` only — plain rows have no `type` key).

## 3. Flag logic engine (shared, already on-device)

Python `FlagSet` / `FlagLogic` (identical to JS `flags.js`):

| Function | Purpose | Parity |
|---|---|---|
| `FlagSet(flags).load(s)` | parse raw/canonical string | ✅ python (fe_bridge.validate_flags) |
| `.set(f)` `.unset(f)` `.has(f)` `.get_list(regex?)` | core mutations/query | ✅ python |
| `.to_string()` | canonical flag string (site order) | ✅ python |
| `.to_binary()` | stable binary string (`b...`, used in seed ids) | ✅ python |
| `.get_version()` | reject cross-version binary strings | ✅ python |
| `FlagLogic().fix(flagset)` | `[[severity, message]…]` severity ∈ info / warning / **error** | ✅ python — must add to bridge |

**The `fix()` log is the site's "There are X logical error(s)…" gate** and its
warnings list. Generating is blocked while any entry has severity `error`.

## 4. Editor state machine (`make_react.js` — the part we re-implement)

Pure data logic, ~200 lines of JS, no DOM until rendering. Ported 1:1 to Kotlin:

1. **`enabledItems`** — the editor's working set: `{flag → true}` for every
   currently-toggled control.
2. **`calculateRequiredContainers()`** — walk the spec tree; whenever a real
   (non-`@`) flag is enabled, mark all ancestor `@` containers enabled (so
   parent sections "open" automatically).
3. **`applyEdits({flag: set|unset, …})`** — mutate `enabledItems`, then rebuild
   the **true flag set**:
   - walk the spec recursively;
   - include a control's flag only if `enabledItems[flag]`
     **or** control type is `select` (select parents always recurse);
   - skip `@` anonymous flags;
   - recurse into `subcontrols` only when the parent was included.
4. **`onFlagsChanged(set)`** → canonicalize + `fix()` (via engine) → update
   summary (canonical string, binary) + logic log + `flagsValid` gate.

## 5. UI components and their behavior

| Component | Behavior to reproduce natively |
|---|---|
| `InitializationPanel` | 3 buttons: **Choose Preset / Input Flag String / Build From Scratch** (the latter clears to defaults). Applies → resets editors + scrolls. Binary `b…` strings get up-converted (legacy, optional). |
| `PresetPanel` / `PresetCategoriesMenu` / `PresetOptions` / `PresetBox` | categories list (skips `header` rows, marks `external`), boxes with name + HTML description + flags; click ⇒ apply flags. *(already native)* |
| `FlagStringInputPanel` | textarea + Apply button; applies raw string via FlagSet.load. *(already native: our flags field + live validation)* |
| `FlagEditor` | state machine from §4 + renders sections. |
| `FlagSection` | section title + control list. |
| `FlagControlList` | dispatch: `type==select` → `FlagSelector`; `type==separator` → divider; else → `FlagControl`. |
| `FlagSelector` | dropdown: current value = first enabled subcontrol (else parent "none" unless `important`); opening shows options; picking sets one + **unsets all siblings**; closes on outside tap. |
| `FlagControl` | toggle row: click flips set/unset; shows description + nested subcontrols when open; classes: `selected`, `hard`, `container` (starts `@`), `compact`. |
| `FlagButton` | icon (✓ if selected & not null; ≡ for select; … for containers), title, flag name. |
| `FlagLogicLogList` | renders each `fix()` entry as a line colored by severity. |
| `GenerateButton` | "Generate" when `flagsValid`, else "There are logical error(s) in your flags…" |
| Summary | canonical string + binary string + seed input. |
| Metaconfig | hide-flags + test settings (quickstart/open/characters/items/gp/noboss/force boss/hobs) → set unique seed, like the site. |
| `generate()` | flags = canonical string from summary, seed, metaconfig → engine. *(native already)* |

## 6. Parity map → native implementation

| Web piece | Native replacement | Status |
|---|---|---|
| uispec.js data | `assets/spec/ui_spec.json` (stripped JSON) | ✅ done |
| FlagSet/FlagLogic | **reuse Python** via fe_bridge (`flags_from_list`, `flag_list`) | ✅ done |
| Editor state machine | `FlagEditorModel.kt` (pure Kotlin port of §4) | ✅ done |
| Section/control renderers | `FlagEditorActivity.kt` (LinearLayout rows, dialogs for selectors) | ✅ done |
| Presets | already native | ✅ done |
| Flag string field + validate | already native (validate_flags) | ✅ done |
| Generate → ROM → RetroArch | already native | ✅ done |
| Metaconfig (hide/test) | native toggles on editor screen | later |
| Legacy binary upconvert | skip (documented limitation) | later |

Tests for parity: same flags input → same canonical + binary + fix() output
(engine is identical); same enabledItems tree as make_react (port is 1:1).