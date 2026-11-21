# On-screen controls — survey of what the best programs do

**Purpose: pick refinements for the FF4 pad from programs that have already solved
this, rather than invent.** Compiled from direct working knowledge of these apps
(no live browsing in this environment); each item credited to the app(s) it comes
from, and each decision below is traceable to a row in this table.

## What the reference programs do

| Program | The ideas worth taking |
|---|---|
| **RetroArch** (Android) | Configurable overlays as data, not code. **Overlay auto-opacity**: the pad dims after a few idle seconds and lights on touch — exactly the balance between "see the game" and "see the buttons". Hold-button variants (fast-forward as hold *or* toggle). |
| **PPSSPP** (Android) | The gold standard for **per-control layout editing**: long-press a control, drag it, scale it, hide it, per-control opacity. Also: buttons glow while pressed, and **"unthrottled" fast-forward shown as a distinct state**, not a mystery. |
| **Dolphin** (Android) | Same editor lineage; **individual show/hide per control**, plus remembering per-game layouts. |
| **DraStic** | **Left-handed swap** as a first-class setting; per-group scale/opacity; adjustable dpad deadzone. |
| **Delta** (iOS) | Skins and **haptics as a toggle**; pressed state is a bright glow so the eye catches the *edge* of a thumb. Notably loved for legibility, not complexity. |
| **Lemuroid** | Restraint: exactly **three settings — size, opacity, vibration** — and a single-surface pad. Its reviews praise what it *left out*. |
| **Snes9x EX+ / MD.emu** (Broglia) | **Multi-touch with slide-off release** and a whole-square dpad (no dead corners); **hold gestures** on console buttons as accelerators (e.g. Select+L = save state) for power users, visible buttons kept for everyone else. |

## The conclusions, ranked

### Tier 1 — now (this round)

1. **Auto-fade when idle** *(RetroArch auto-opacity)* — pad at full opacity while
   touched, dims after ~3 s of no touches, returns instantly on any touch. The
   game is the thing being played; buttons are a tool. Setting: on by default,
   can be turned off.
2. **Left-handed swap** *(DraStic)* — mirrors d-pad and face buttons. Cheap, and
   the single most-asked-for option in every emulator's issue tracker.
3. **Inset-aware bottom margin** — on gesture-nav devices the bottom edge belongs
   to the system swipe; the pad lifts itself clear of it rather than fighting.
4. Keep Lemuroid's restraint: the settings window stays **one screen, five
   options** — size, opacity, haptics, fade, swap. No per-control editor yet.

### Tier 2 — next candidate, not now

5. **Per-control layout editor** *(PPSSPP/Dolphin)* — long-press a button, drag
   and scale it, remember per game. Powerful, but it is a whole editor with its
   own bug surface; wrong to build it before the default is validated on real
   hands. Revisit once the default pad has community feedback.
6. **Hold gestures on console buttons** *(Broglia)* — e.g. hold SAVE = load-state
   slot cycle. Only after feedback shows the visible rows feel crowded; our
   visible-row approach is deliberately discoverable for a first-time FE player.

### Tier 3 — deliberately not doing

7. **Custom skins** *(Delta)* — a skin *format* is a project; the FF4 window look
   is the app's identity and the pad already shares it.
8. **Analog stick mode** — FF4 is a four-direction game; a stick would be a
   regression, not a feature.

## What we already have, and where it came from

* Single surface, every pointer, slide-around d-pad, reference-counted double
  presses — the Broglia multi-touch model, as a tested `:core` state machine.
* Size, opacity, haptics — Lemuroid's three.
* Pressed states brighten their border — Delta's "catch the edge" legibility.
* Taps that cancel when the finger slides away — every good pad does this; few
  state it.
