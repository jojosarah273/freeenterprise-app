# Where the player's base ROM is - shared by the tarball launcher and the AppImage's
# AppRun, so the two builds cannot disagree about it.
#
# The ROM is never shipped: it is the player's own FF2 (US) v1.0 or v1.1, identified by
# MD5 rather than by name, because dumps are named anything ("Final Fantasy II (USA).sfc",
# "ff4.smc", ...) and some carry a 512-byte copier header.
#
# Expects $HERE to be the folder the player sees: the install root for the tarball, the
# folder holding the .AppImage for the AppImage. FF4_FE is checked first, since that is
# where a seed library keeps its files.
MD5_V10=4fa9e542b954dcb954d5ce38188d9d41
MD5_V11=27d02a4f03e172e029c9b82ac3db79f7

looks_like_base_rom() {
    local file="$1" md5
    md5="$(md5sum "$file" | cut -d' ' -f1)"
    [ "$md5" = "$MD5_V10" ] || [ "$md5" = "$MD5_V11" ] && return 0
    # Some dumps carry a 512-byte copier header; the ROM itself starts after it.
    md5="$(tail -c +513 "$file" | md5sum | cut -d' ' -f1)"
    [ "$md5" = "$MD5_V10" ] || [ "$md5" = "$MD5_V11" ]
}

find_base_rom() {
    local candidate
    for candidate in "$HERE"/FF4_FE/ff2.smc "$HERE"/FF4_FE/ff2.sfc \
                     "$HERE"/ff2.smc "$HERE"/ff2.sfc "$HERE"/*.smc "$HERE"/*.sfc; do
        [ -f "$candidate" ] || continue
        if looks_like_base_rom "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
    done
    return 1
}

# Prints what was found, or what to do about it. Everything except generating a seed
# works without a base ROM, so this is advice, not an error.
announce_base_rom() {
    if base_rom="$(find_base_rom)"; then
        echo "base ROM: $(basename "$base_rom")"
        BASE_ROM="$base_rom"
    else
        echo "No base ROM found next to this ${1:-build}."
        echo "  Put your own FF2 (US) v1.0/v1.1 ROM here as ff2.smc (or .sfc)."
        echo "  Everything except generating a seed works without it."
        BASE_ROM=""
    fi
}
