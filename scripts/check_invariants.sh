#!/usr/bin/env bash
# Guards against changes that look applied but are not.
#
# Three separate bugs shipped because a patch "applied" (the property, call site
# or file was there) while the behaviour was still wrong or the code had silently
# vanished:
#   1. the "Check for updates" menu entry was lost in a bulk rewrite
#   2. the spoiler file was never written — the write existed in no commit
#   3. the progress heading was hardcoded in update(), clobbering the caller's title
#   4. the key-item parser required three dots, truncating an 18-entry section to 9
# Each assertion below fails loudly if one of those regresses. Run before releasing.
set -uo pipefail
cd "$(dirname "$0")/.."
J=app/src/main/java/com/freeenterprise/app
fails=0
ok()   { printf '  \033[32mok\033[0m   %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fails=$((fails+1)); }
has()    { grep -q -- "$2" "$1" && ok "$3" || bad "$3"; }
hasnot() { grep -q -- "$2" "$1" && bad "$3" || ok "$3"; }

echo "Progress overlay"
has    "$J/ff4ui/FF4ProgressOverlay.kt" 'stageText.text = title' \
       "update() uses the caller's title"
hasnot "$J/ff4ui/FF4ProgressOverlay.kt" 'stageText.text = "' \
       "no hardcoded heading in the overlay"
has    "$J/FF4MainActivity.kt" 'title = "DOWNLOADING UPDATE"' "APK download labels its window"
has    "$J/FF4MainActivity.kt" 'title = "UPDATING ENGINE"'    "engine update labels its window"

echo "Stable menu geometry"
has    "$J/ff4ui/FF4Activity.kt"     'pinBoxHeights'          "boxes are pinned to a fixed height"
has    "$J/ff4ui/FF4MenuActivity.kt" 'pinBoxHeights'          "the menus pin them"
has    "$J/FF4FlagEditorActivity.kt" 'pinBoxHeights'          "the flag editor pins them too"
hasnot "$J/ff4ui/FF4MenuActivity.kt" 'View.GONE'              "the status slot is never collapsed"
hasnot app/src/main/res/layout/activity_ff4_menu.xml 'visibility="gone"' "the status slot reserves its space in XML"
has    app/src/main/res/values/themes.xml 'windowAnimationStyle' "menu switches are not animated"
has    "$J/FF4MainActivity.kt"       'defaultStatus'          "the main menu fills its status box"

echo "Install-safe manifest"
# A hardcoded authority collides with the installed public app, and Android then
# reports only "App not installed". Every build must own its own names.
has    app/src/main/AndroidManifest.xml '${applicationId}.fileprovider' "the FileProvider authority follows the application id"
hasnot app/src/main/AndroidManifest.xml 'authorities="com.freeenterprise.app.fileprovider"' "no hardcoded authority"
has    "$J/RetroArchLauncher.kt" 'packageName}.fileprovider' "and the runtime lookup follows the package"

echo "Dev build identity"
has    app/build.gradle.kts 'applicationIdSuffix = ".dev"' "a test build installs beside the public app"
has    app/build.gradle.kts 'versionNameSuffix = "-dev"'  "and says so in the version string"

echo "Desktop build"
has    scripts/make_linux_test_build.sh 'python_ext'                       "the Linux build ships the engine"
has    desktop/native/Makefile        'libff4input.so'                   "controller support is built"
has    desktop/native/Makefile        'Makefile.common'                  "the Linux core shares the Android source list"
has    desktop/native/Makefile        'ff4emu.cpp'                       "and reuses the Android bridge file"
has    desktop/native/shim/android/log.h '__android_log_print'           "with only a log shim for Linux"
has    desktop/src/main/kotlin/com/freeenterprise/app/emulator/EmulatorBridge.kt 'package com.freeenterprise.app.emulator' "the desktop bridge matches the JNI symbol names"

echo "ff4dump harness"
has    tools/ff4dump/Makefile 'Makefile.common'   "the harness shares the Android source list"
has    tools/ff4dump/main.cpp 'Tracker__KeyItemFoundFlags' "and decodes the engine's flags"

echo "Portable core"
has    "$J/ff4ui/FF4Window.kt" 'Ff4WindowChrome.of'            "window chrome comes from the core"
has    core/src/main/kotlin/com/freeenterprise/ff4/Ff4WindowChrome.kt 'Ff4WindowChrome' "the core owns the chrome model"
has    core/src/main/kotlin/com/freeenterprise/ff4/Ff4Scale.kt       'SNES_SHORT_EDGE_PX' "the core owns the layout unit"
has    core/src/main/kotlin/com/freeenterprise/ff4/Ff4Palette.kt     'BLUE_SELECTED'      "the core owns the palette"
has    core/src/main/kotlin/com/freeenterprise/ff4/Ff4Menu.kt        'Ff4MenuModel'       "the core owns menu navigation"
has    "$J/ff4ui/FF4Menu.kt"       'Ff4MenuModel'                "and the menu uses it"
has    core/src/main/kotlin/com/freeenterprise/ff4/Ff4ProgressBarChrome.kt 'Ff4ProgressBarChrome' "the core owns the progress bar"
has    "$J/ff4ui/FF4ProgressBar.kt" 'Ff4ProgressBarChrome.of'     "and the bar uses it"
if grep -rq 'android\.\|java\.awt\|javax\.swing\|java\.io\|java\.nio' core/src/main/kotlin 2>/dev/null; then
  bad "the core must not depend on Android, AWT, Swing or file IO"
else
  ok "the core is pure: no Android, AWT, Swing or file IO"
fi

echo "Screen edges and icon"
has    "$J/ff4ui/FF4Activity.kt"     'settleEdgeBuffer'         "menus keep clear of rounded corners"
has    "$J/ff4ui/FF4MenuActivity.kt" 'settleEdgeBuffer'         "and settle it before the first frame"
hasnot "$J/ff4ui/FF4Activity.kt"     'if (keepsScreenEdgeBuffer) applyEdgeBuffer()' "never after the first frame"
hasnot "$J/FF4PlayerActivity.kt"     'keepsScreenEdgeBuffer'    "the emulator play screen is NOT insetted"
has    "$J/ff4ui/FF4MenuActivity.kt" 'keepsScreenEdgeBuffer'    "the menus opt in"
has    "$J/FF4FlagEditorActivity.kt" 'keepsScreenEdgeBuffer'    "the flag editor opts in"
has    scripts/make_icons.py         'emblem_crop'              "legacy icons crop to the artwork"
has    scripts/make_icons.py         'content_box'              "the icon script finds the emblem"
hasnot scripts/make_icons.py         'legacy = scaled(src,'     "legacy icons are not the raw canvas"

echo "Update path"
has    "$J/FF4MainActivity.kt" 'Check for updates'        "the update menu entry exists"
has    "$J/UpdateChecker.kt"   'api.github.com'           "release feed is queried"
has    app/src/main/AndroidManifest.xml 'android.permission.INTERNET'              "INTERNET declared"
has    app/src/main/AndroidManifest.xml 'REQUEST_INSTALL_PACKAGES'                 "install permission declared"

echo "Back never leaves the app by accident"
has    "$J/ff4ui/FF4MenuActivity.kt" 'menuLayer = key'        "the menu layer is tracked"
has    "$J/FF4MainActivity.kt"       'menuLayer != "main"'    "back goes up a layer"
has    "$J/FF4MainActivity.kt"       'confirmQuit'            "and leaving is confirmed"
has    "$J/FF4MainActivity.kt"       'Leave Free Enterprise'   "the confirmation says what is kept"
has    "$J/ff4ui/FF4MenuActivity.kt" 'KEYCODE_BUTTON_B'       "pad B counts as Back"
# The bug on devices: 0.1.25 was built before the layer tracking, so the update
# check's results screen closed the app on Back. The old branch is the regression.
hasnot "$J/FF4MainActivity.kt"       'if (inSettings) {'       "no settings-only Back branch"

echo "Key-item tracking"
has    "$J/FF4GenerateActivity.kt" 'keyItems = Spoiler.keyItemLocations' "generation records key items"
has    "$J/FF4GenerateActivity.kt" 'RomLibrary.save('                    "generation saves the seed"
has    "$J/RomLibrary.kt"          '"key_items"'                         "metadata serialises key items"
has    "$J/FF4PlayerActivity.kt"   'meta?.keyItems'                      "tracker reads them from metadata"
has    "$J/FF4PlayerActivity.kt"   'not available'                       "placeholders are filtered"
has    "$J/FF4PlayerActivity.kt"   'isFreeStartingItem'                 "free starting item is always ticked"
has    "$J/FF4PlayerActivity.kt"   'KEY_ITEM_FOUND_OFFSET = 0x500'      "the engine's found flags are read"
has    "$J/FF4PlayerActivity.kt"   'feKeyItemFound'                     "page 1 ticks from the engine, not the bag"
has    "$J/FF4PlayerActivity.kt"   'FE_KEY_ITEM_INDEX'                  "the engine's 17-item table is present"
has    "$J/FF4PlayerActivity.kt"   '"legendsword" to 2'                 "incl. Legend, which the bag could never see"
has    "$J/FF4PlayerActivity.kt"   '"spoon" to 14'                      "incl. Spoon, likewise"
has    "$J/FF4PlayerActivity.kt"   '"crystal" to 16'                    "incl. Crystal, likewise"
has    "$J/FF4PlayerActivity.kt"   '"hook" to 8'                        "incl. Hook, likewise"
has    "$J/FF4PlayerActivity.kt"   'seenKeyItems'                       "obtained items are remembered"
hasnot "$J/FF4PlayerActivity.kt"   'held.contains(normalise(name))'     "no inventory-only check (un-ticks traded items)"
has    "$J/RomLibrary.kt"          '"seen_key_items"'                   "metadata stores what was obtained"
hasnot "$J/Spoiler.kt"             '\\\\\\.{3,}'                          "parser accepts short dot runs"

echo "Menus are defined once, and dispatched by action"
# Both platforms dispatch the *shared* menus on Ff4Action. Dispatching on a row index
# is how the desktop Exit row came to check for updates: the platforms offered
# different rows, so position 2 meant different things. Platform-local menus (the
# update results, a .srm picker) may still use indices - they exist on one platform.
A=app/src/main/java/com/freeenterprise/app
D=desktop/src/main/kotlin/com/freeenterprise/desktop/Main.kt
has    "$A/FF4MainActivity.kt"     'rows.getOrNull(i)?.action'      "Android main menu: by action"
has    "$A/FF4PlayActivity.kt"     'rows.getOrNull(i)?.action'      "Android session/save menus: by action"
has    "$A/FF4GenerateActivity.kt" 'rows.getOrNull(i)?.action'      "Android generate/completed: by action"
has    "$A/FF4PlayerActivity.kt"   'rows.getOrNull(index)?.action'  "Android pause menu: by action"
hasnot "$A/FF4MainActivity.kt"     'submitMenu("main", Ff4Rows'     "Android main menu is not index-built inline"
has    "$D" 'Ff4Action.EXIT_APP'                                  "desktop: Exit quits, whatever its position"
has    "$D" 'libraryRows().getOrNull(libraryModel.selected)?.action' "desktop main menu: by action"
has    "$D" 'generateRows().getOrNull(flagSelected)?.action'       "desktop generate: by action"
has    "$D" 'actionRows().getOrNull(actionsSelected)?.action'      "desktop session actions: by action"
has    "$D" 'pauseRows().getOrNull(index)?.action'                 "desktop pause menu: by action"
hasnot "$D" 'when (libraryModel.activationTarget())'              "desktop main menu is not dispatched by index"
# Rows are built only from the shared definitions, never by a platform.
owned=0
for f in "$A"/*.kt "$D" ; do
    if grep -q 'Ff4Row(' "$f"; then bad "$(basename "$f") builds its own rows instead of using Ff4Rows"; owned=1; fi
done
[ "$owned" -eq 0 ] && ok "rows come from Ff4Rows only"
# The editor's three-line detail box must be reserved by the layout, or it covers the menu.
has "$D" 'hintLines = detail.size'                                   "editor reserves its detail box height"
has "core/src/main/kotlin/com/freeenterprise/ff4/Ff4ScreenLayout.kt" 'hintLines: Int = 1' \
    "the layout takes the hint line count"

echo "The base ROM is found the same way in both builds"
# Two copies of this logic drifted once already: the AppImage looked only for ff2.smc
# with no MD5 check, so a .sfc or a differently-named dump was ignored.
[ -f scripts/rom-discovery.sh ] && ok "one definition exists" || bad "scripts/rom-discovery.sh is missing"
for b in scripts/make_linux_test_build.sh scripts/make_linux_appimage.sh; do
    has "$b" 'scripts/rom-discovery.sh' "$(basename "$b"): splices the shared discovery"
    has "$b" '@@ROM_DISCOVERY@@'        "$(basename "$b"): has a splice point"
done
hasnot scripts/make_linux_appimage.sh 'MD5_V10=' "the AppImage does not carry its own copy"
hasnot scripts/make_linux_test_build.sh 'MD5_V10=' "the launcher does not carry its own copy"
has scripts/rom-discovery.sh 'tail -c +513'           "a 512-byte copier header is tolerated"
has scripts/rom-discovery.sh '"$HERE"/\*.smc'         "any .smc beside the file is considered"
has scripts/rom-discovery.sh '"$HERE"/\*.sfc'         "any .sfc beside the file is considered"

echo "The playing screen does not fight itself"
# The bug that froze the picture: the loop only repaints, so the panel must be given
# a way to fetch the live game image. It defaulted to "no direct frame", so every
# repaint redrew a canvas nobody was filling - the screen sat still until a keypress.
has desktop/src/main/kotlin/com/freeenterprise/desktop/Main.kt 'gamePanel(canvas) {' \
    "the panel is wired to the live game picture"

# Three separate reasons the picture stuttered, crawled or ignored the pad.
D=desktop/src/main/kotlin/com/freeenterprise/desktop
has "$D/Main.kt"       'pad.menuOpen = true'      "the pad is wired while in the menus"
has "$D/Main.kt"       'pad.tick()'               "the pad is ticked, not only polled"
has "$D/Main.kt"       'directFrame'              "the game picture is blitted straight to the window"
has "$D/Main.kt"       'panel.repaint()'          "the loop repaints instead of recomposing"
has "$D/GameSession.kt" 'nativeClearAudio'        "audio is consumed, not accumulated"
has "$D/GameSession.kt" 'FAST_FORWARD_SPEED'      "fast-forward is paced above real time"
has "$D/GameSession.kt" 'if (fastForward) EmulatorBridge.nativeClearAudio()' \
    "fast-forward drops audio instead of being paced by it"

echo "Desktop handlers"
# A lateinit handler whose assignment was deleted by a bulk edit fails only when the
# row is pressed: "Choose Flags does not load", with no compile error. That is how
# generateActivate disappeared in d1c4cc6, so every handler is checked here.
M=desktop/src/main/kotlin/com/freeenterprise/desktop/Main.kt
for v in $(grep -oP 'lateinit var \K\w+' "$M"); do
    if grep -qE "^[[:space:]]*$v = " "$M"; then
        ok "$v is assigned"
    else
        bad "$v is declared but never assigned (pressing its row throws)"
    fi
done

echo "Versioning"
name=$(sed -n 's/.*versionName = .*"\([0-9][^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
# versionCode is an expression with a numeric fallback, so grep for a leading digit
# returns nothing; take the last number on the line instead.
code=$(sed -n 's/.*versionCode = .*\([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | tail -1)
printf '  info versionName %s (versionCode %s)\n' "$name" "$code"
if [ "${1:-}" != "" ]; then
  [ "$name" = "$1" ] && ok "matches tag $1" || bad "build.gradle.kts says $name, tag says $1"
fi

echo
[ "$fails" -eq 0 ] && { echo "all invariant checks passed"; exit 0; }
echo "$fails invariant check(s) FAILED"; exit 1
