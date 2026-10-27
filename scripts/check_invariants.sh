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

echo "Versioning"
name=$(sed -n 's/.*versionName = .*"\([0-9][^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
code=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
printf '  info versionName %s (versionCode %s)\n' "$name" "$code"
if [ "${1:-}" != "" ]; then
  [ "$name" = "$1" ] && ok "matches tag $1" || bad "build.gradle.kts says $name, tag says $1"
fi

echo
[ "$fails" -eq 0 ] && { echo "all invariant checks passed"; exit 0; }
echo "$fails invariant check(s) FAILED"; exit 1
