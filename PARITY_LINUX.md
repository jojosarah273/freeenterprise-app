# Android 0.1.25 -> Linux parity audit

Every screen, row, value, status line and hint compared against the Android source
(`FF4MainActivity`, `FF4PlayActivity`, `FF4GenerateActivity`, `FF4PresetActivity`,
`FF4FlagEditorActivity`, `FF4PlayerActivity`). Legend: **same** = identical rows,
values, wording and behaviour; **platform** = deliberately different because the
platform differs; **missing** = not implemented.

## Main menu

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Play Free Enterprise, value "N seed(s)" | same | same |
| Generate Seed | same | same |
| Check for updates, value "v0.1.25" | - | platform (no desktop update channel yet) |
| Settings | - | platform (no desktop settings screen) |
| Exit, confirmed | same, confirmed | same |
| Status: engine version, seed count, internet warning | engine version, seed count, pad status | same in spirit |
| Hint "A  Select      B  Exit" | same | same |

## Sessions (Play Free Enterprise)

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| One row per seed: name, value "NNNNK" | same | same |
| Subtitle "seed X  ·  [icons]  ·  save NN K  ·  last played DATE" | same order: text then icons | same |
| Empty list: "(no seeds)" disabled + Back | same | same |
| Status "FF4_FE  ·  N session(s)" | same | same |
| Hint "A  Select      B  Back" | same | same |

## Session actions

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Play Seed | same | same |
| Manage Save, "present"/"none" | same | same |
| New seed with these flags, "same ruleset" | same | same |
| Save flags as Flagset | same, prompts for a name | same |
| Rename Seed | same, prompts; renames every file beside it | same |
| Delete Seed, confirmed "Delete \"name\", its ROM and its save from FF4_FE?" | same wording | same |
| Back | same | same |

## Manage Save

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Play in-app (Snes9x) | - | platform (the desktop *is* the emulator) |
| Export save (.srm) | same, native file chooser | same |
| Import save (.srm) | same | same |
| Delete save, confirmed "Delete the .srm save for this session? The ROM stays." | same wording | same |
| Back | same | same |

## Generate

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Choose Preset, value = preset name | same | same |
| Choose Flags, "none set" when empty | same | same |
| Seed, "random" when blank; blank is left to the engine | same (engine returns e.g. ELS5JYMQY9) | same |
| Generate Seed, "fix errors" when invalid | same | same |
| Back | same | same |
| Status: canonical flags + "Flags OK" / errors | same | same |
| Hint "A  Select      B  Back" | same | same |
| After generating: Play Free Enterprise, Generate Again, Back | same | same |

## Presets

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| "My flagsets" first, value "N saved" | same | same |
| Category rows, value = preset count | same | same |
| Category page: preset name + description | same | same |
| My flagsets: rulesets, "Delete a flagset...", Back | same | same |
| Delete list, then confirmation | same | same |
| Applying returns to Generate | same | same |
| Status/hints ("Presets - pick a category", "A Open   B Back", "A Apply   B Back", "A Delete   B Back") | same | same |

## Flag editor

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Level 0 categories, value "N on" | same | same |
| Level 1 controls, ON/OFF, indents, children revealed when on | same | same |
| Level 2 selector, "(none)" first unless important, chosen row ON, exclusive | same | same |
| Select row shows its current option or "none"; the option's own controls listed beneath | same | same |
| Status: canonical flags + "Flags OK" (green) / "N logical error(s)" (pink) | same, from the same engine call | same |
| Three-line hint: title [flag], description, keys | same | same |
| Cursor remembered per level | same | same |

## Play (pause menu)

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Resume | same | same |
| Save State: saves then returns to the game | same | same |
| Load State: "load"/"none", disabled when absent; loads then returns | same | same |
| Fast-forward: "on"/"off", toggles and redraws the row | same | same |
| Checks tracker, "L2" | same, "Tab" (keyboard) | same in spirit |
| Reset Core, confirmed | same | same |
| Exit to sessions, confirmed "Leave the game? Your battery save is written to FF4_FE first." | same wording | same |
| Yellow state line "State: X.state · NN K · date" | same | same |
| Menus pause the emulator | same | same |

## Tracker

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| "PAGE 1 / 2   KEY ITEM CHECKS", locations with ticks, "N / M checked", legend | same | same |
| Ticks the engine's found flags, the free starting item, and items held or seen | same | same |
| Remembered in the seed's `seen_key_items` | same | same |
| "PAGE 2 / 2   CHECKS", TOTAL, worlds, "BY AREA (most remaining first)" | same | same |
| Left/Right pages | same | same |

## Data and engine

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| `FF4_FE` folder with `<name>.smc/.json/.srm/.state/.spoiler.txt`, `flagsets.json` | same, at the installation root | same |
| Seeds stored as `<name>.smc`, never the engine's binary-flags name | same | same |
| Metadata keys identical to `SeedMeta.toJson` | same | same |
| Engine 4.7.0.Gale, bundled | same engine directory | same |
| Old (4.6) flags translated | same (sanitised at the shared entry point) | same |
| Engine version shown | same | same |
| Base ROM: document picker with MD5 check | file beside the app or in FF4_FE | platform |
| Engine updates / rollback | - | missing |
| App version in the status line | - | missing |
| On-screen pad, RetroArch launch | - | platform |

## Text input

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Prompts for a seed name, a flagset name, a rename | same, keyboard-only field in an FF4 window; Enter accepts, Esc cancels, it owns the keyboard | same |

## One source of truth for the rows

Every menu's rows are now defined once, in `:core` (`Ff4Rows`): main menu, session
actions, Manage Save, Generate, the completion screen and the in-game menu. Each
client maps them to its own menu items and keeps its own actions, because actions are
genuinely platform-specific (an activity or document picker against a screen or file
chooser); labels, values, order and enabled state are shared. Core tests assert those
against the Android wording, so a row cannot gain a value on one platform and not the
other - which is exactly how "Rename Seed" acquired a long file name, and Manage Save
drifted in order and values.

Platform values are parameters: the tracker's shortcut key ("L2" / "Tab"), and whether
the main menu has update and settings rows.

## Remaining work, in priority order

1. **Engine updates and rollback** (Android swaps its engine silently and can restore
   the previous one).
2. **Base ROM picker** on the desktop, with the MD5 validation the app uses.
3. **App version** in the status line alongside the engine version.
4. Platform rows with no desktop counterpart yet: Check for updates, Settings.

Everything else above is marked `same`, and the differences that remain are platform
differences (no touch screen, the desktop is its own emulator, file access instead of
SAF), not drift between two implementations of the same interface.
