# Offline Free Enterprise engine bridge.
#
# This module is imported from Chaquopy's bundled python path (src/main/python)
# after Kotlin inserts the extracted repo directory into sys.path. It mirrors
# the seed-generation flow of the website's server (FreeEnt/server/generator.py
# + website.py) so seeds reproduce the site's output for the same flags/seed.
import io
import json
import os
import random
import re
import time

RANDOM_SEED_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ0123456789'

# Same values as the repo's .env, required for seed parity with the site.
_FE_SALT = 'GALE'
_FE_SPOILER_SALT = 'SPOILER_GALE'
_FE_TEST_SALT = 'TEST_GALE'
_FE_EMBEDDED_REPORT_KEY = 'EMBEDDED_REPORT_KEY---------GALE'

_ENV_SET = False


def _ensure_env():
    global _ENV_SET
    if _ENV_SET:
        return
    for key, value in [
        ('FE_SALT', _FE_SALT),
        ('FE_SPOILER_SALT', _FE_SPOILER_SALT),
        ('FE_TEST_SALT', _FE_TEST_SALT),
        ('FE_EMBEDDED_REPORT_KEY', _FE_EMBEDDED_REPORT_KEY),
    ]:
        os.environ.setdefault(key, value)
    _ENV_SET = True


def engine_version():
    """The engine version string currently on disk (e.g. '4.7.0.Gale')."""
    try:
        _ensure_env()
        import FreeEnt.version as v
        return json.dumps({'status': 'ok', 'version': v.VERSION_STR})
    except Exception as exc:  # noqa: BLE001
        return json.dumps({'status': 'error', 'error': _err(exc)})


def reload_engine():
    """Make a swapped-in engine take effect in the running interpreter.

    Chaquopy starts CPython once per process and caches imported modules, so new
    files on disk stay invisible until we drop the cached modules AND purge the
    __pycache__ directories Python wrote at runtime. Called after an in-app
    engine update; harmless when nothing changed.
    """
    import sys
    import os
    import shutil
    try:
        dropped = 0
        for name in list(sys.modules):
            if name.split('.')[0] in ('FreeEnt', 'f4c'):
                sys.modules.pop(name, None)
                dropped += 1
        purged = 0
        for base in [p for p in sys.path if 'python_ext' in p]:
            for root, dirs, _files in os.walk(base):
                for d in list(dirs):
                    if d == '__pycache__':
                        shutil.rmtree(os.path.join(root, d), ignore_errors=True)
                        dirs.remove(d)
                        purged += 1
        return json.dumps({'status': 'ok', 'dropped': dropped, 'purged': purged})
    except Exception as exc:  # noqa: BLE001
        return json.dumps({'status': 'error', 'error': _err(exc)})


def set_ext_path(path):
    """Ensure the extracted repo directory is importable. Kept as a tiny Python
    function so Kotlin never has to chain callAttr through sys.path (a PyList),
    which trips Chaquopy's Java-side list-method dispatch."""
    import sys
    if path not in sys.path:
        sys.path.insert(0, path)



# ---------------------------------------------------------------------------
# Generation progress
#
# The heavy part of generation is f4c's script compiler, and compile() wraps
# every phase (and every source block) in Metrics.measure(...). We hook
# Metrics.start from *our* code, so we get real phase/block progress without
# editing the vendored engine. Progress is written to a small file that the UI
# polls (plain file I/O — no cross-thread Chaquopy calls while Python blocks).
# ---------------------------------------------------------------------------
_PROGRESS_PATH = None
_progress_last = None
_progress_blocks = 0


def set_progress_path(path):
    """Called by the app before generate(); None disables progress reporting."""
    global _PROGRESS_PATH, _progress_last, _progress_blocks
    _PROGRESS_PATH = path
    _progress_last = None
    _progress_blocks = 0


def _write_progress(pct, stage, detail=''):
    global _progress_last
    if not _PROGRESS_PATH:
        return
    key = (pct, stage, detail)
    if key == _progress_last:
        return
    _progress_last = key
    try:
        with open(_PROGRESS_PATH, 'w') as out:
            out.write('%d\n%s\n%s\n' % (pct, stage, detail))
    except Exception:
        pass


def _on_phase(name):
    """Map an f4c compile phase to a progress percentage."""
    global _progress_blocks
    if name == 'load rom':
        _write_progress(12, 'Loading ROM')
    elif name == 'load scripts':
        _write_progress(16, 'Reading scripts')
    elif name == 'read code blocks':
        _write_progress(20, 'Parsing scripts')
    elif name.startswith('process '):
        _progress_blocks += 1
        # Block processing dominates; approach 88% asymptotically so the bar
        # keeps moving even if the block count varies between seeds.
        pct = 20 + int(68 * (1.0 - 2.718281828 ** (-_progress_blocks / 260.0)))
        _write_progress(min(pct, 88), 'Compiling', name[len('process '):])
    elif name == 'bytes patches':
        _write_progress(90, 'Applying patches')
    elif name == 'postprocess':
        _write_progress(94, 'Post-processing')
    elif name == 'output':
        _write_progress(97, 'Writing ROM')


def _install_progress_hook():
    # NB: `f4c.compile` is re-exported as a *function* by f4c/__init__.py, so we
    # reach the module through sys.modules.
    import sys
    try:
        import f4c  # noqa: F401  (ensures the submodule is imported)
        f4c_compile = sys.modules.get('f4c.compile')
    except Exception:
        return
    if f4c_compile is None:
        return
    metrics_cls = getattr(f4c_compile, 'Metrics', None)
    if metrics_cls is None or getattr(metrics_cls, '_ff4_progress_hooked', False):
        return
    original_start = metrics_cls.start

    def start(self, name):
        try:
            _on_phase(name)
        except Exception:
            pass
        return original_start(self, name)

    metrics_cls.start = start
    metrics_cls._ff4_progress_hooked = True


def validate_flags(flags):
    """Parse + canonicalize a flag string using the same FlagSet as the site.

    Returns JSON: {"status": "ok", "flags": canonical, "binary_flags": ...}
              or: {"status": "error", "error": "Parse error around ..."}
    """
    try:
        _ensure_env()
        import FreeEnt
        flagset = FreeEnt.FlagSet(flags)
        return json.dumps({
            'status': 'ok',
            'flags': flagset.to_string(),
            'binary_flags': flagset.to_binary(),
        })
    except Exception as exc:  # noqa: BLE001
        return json.dumps({'status': 'error', 'error': _err(exc)})


def _err(exc):
    import traceback
    tb = traceback.format_exc()
    # last meaningful line is the traceback final "TypeError: ..." or origin line
    return str(exc) + " :: " + tb.strip().splitlines()[-1] if tb.strip() else str(exc)


# ---------------------------------------------------------------------------
# Flag-string compatibility
#
# The presets upstream still ships (and the live site still serves) are written
# against the 4.6 flag spec, and so are many flag strings people share. Rather
# than refuse them, translate what we can and drop what no longer exists —
# reporting everything, so nothing changes silently.
#
# Rules are applied at *piece* level (the comma-separated parts of a token) and
# may be scoped to a superflag, because the same word can be valid elsewhere:
# "fusoya" is dead as "-vanilla:fusoya" but perfectly valid in "Cno:fusoya".
# ---------------------------------------------------------------------------

# (superflag or None for any, piece) -> replacement flag
PIECE_REWRITES = {
    ('-vanilla', 'growup'):  '-call:vanillagrowup',
    ('-vanilla', 'hobs'):    '-call:vanillahobs',
    ('-vanilla', 'agility'): 'Avanilla',
    ('-vanilla', 'fusoya'):  'Fvanilla',
    ('-exp', 'noboost'):     'Xnoboost',
    # subflags that became flags of their own in 4.7
    (None, 'j:abilities'):   'Cabilities:j',
    (None, 'j:spells'):      'Cspells:j',
    (None, 'abilities'):     'Cabilities:j',
    (None, 'spells'):        'Cspells:j',
    (None, 'miab'):          'Kmiab:standard',
}

# whole tokens that were renamed outright
TOKEN_REWRITES = {
    'Cj:abilities': 'Cabilities:j',
    'Cj:spells':    'Cspells:j',
    'Kmiab':        'Kmiab:standard',
}

# flags with no 4.7 equivalent at all
KNOWN_DEAD = {'-vanilla:agility'}


def _superflag_of(flag):
    """The superflag part of a flag name ('-vanilla:x' -> '-vanilla')."""
    return flag.split(':')[0] if ':' in flag else flag


def _rewrite_pieces(text):
    """Translate and lift out subflags; returns (text, renamed, dropped)."""
    renamed, dropped = [], []
    out_tokens = []

    for token in text.split():
        if token in TOKEN_REWRITES:
            target = TOKEN_REWRITES[token]
            renamed.append({'from': token, 'to': target})
            out_tokens.append(target)
            continue

        segments = token.split('/')
        # the superflag lives on the first segment ('-vanilla:giant'); later
        # segments inherit it
        head = segments[0]
        superflag = _superflag_of(head) if ':' in head else None
        rebuilt = []

        for index, segment in enumerate(segments):
            if ':' in segment and index == 0:
                prefix, pieces = segment.split(':', 1)
            else:
                prefix, pieces = None, segment

            kept = []
            for piece in pieces.split(','):
                target = PIECE_REWRITES.get((superflag, piece)) or PIECE_REWRITES.get((None, piece))
                if target is None:
                    kept.append(piece)
                    continue
                if target in KNOWN_DEAD:
                    dropped.append(piece)
                    continue
                if _superflag_of(target) != (superflag or prefix):
                    # promoted to a flag of its own: emit it separately
                    out_tokens.append(target)
                else:
                    kept.append(target.split(':', 1)[1] if ':' in target else target)
                renamed.append({'from': piece, 'to': target})

            if kept:
                rebuilt.append((prefix + ':' if prefix else '') + ','.join(kept))

        if rebuilt:
            out_tokens.append('/'.join(rebuilt))

    return ' '.join(out_tokens), renamed, dropped


def sanitize_flags(flag_string):
    """Make a flag string loadable: translate renamed flags, drop dead ones, and
    report exactly what changed."""
    import re as _re
    try:
        _ensure_env()
        from FreeEnt import flags as flagmod

        text, renamed, dropped = _rewrite_pieces(flag_string or '')

        # Anything still unrecognised is removed by name — the engine tells us
        # which names it rejected, which also covers composite forms we do not
        # know about.
        for _ in range(50):
            try:
                flagset = flagmod.FlagSet(text)
                break
            except Exception as exc:  # noqa: BLE001
                match = _re.match(r'Invalid flags:\s*(.+)$', str(exc))
                if not match:
                    return json.dumps({'status': 'error', 'error': str(exc)})
                removed = False
                for name in [n.strip() for n in match.group(1).split(',') if n.strip()]:
                    pattern = r'(?<!\S)' + _re.escape(name) + r'(?!\S)'
                    if _re.search(pattern, text):
                        text = _re.sub(pattern, '', text)
                        dropped.append(name)
                        removed = True
                if not removed:
                    return json.dumps({'status': 'error', 'error': str(exc)})
        else:
            return json.dumps({'status': 'error', 'error': 'could not sanitise flag string'})

        return json.dumps({
            'status': 'ok',
            'flags': flagset.to_string(),
            'dropped': dropped,
            'renamed': renamed,
        })
    except Exception as exc:  # noqa: BLE001
        return json.dumps({'status': 'error', 'error': _err(exc)})


def check_flags(flag_string):
    """Canonicalize a flag string AND run the logic checks in one call — used by
    the Generate screen to show the current flagset and its validity."""
    try:
        _ensure_env()
        from FreeEnt import flags as flagmod
        # Same sanitisation as generate(), so the editor shows the same verdict the
        # generation would reach.
        cleaned = json.loads(sanitize_flags(flag_string))
        if cleaned.get('status') != 'ok':
            return json.dumps(cleaned)
        flagset = flagmod.FlagSet(cleaned['flags'])
        log = flagmod.FlagLogic().fix(flagset)
        has_error = any(entry[0] == 'error' for entry in log)
        return json.dumps({
            'status': 'ok',
            'flags': flagset.to_string(),
            'binary_flags': flagset.to_binary(),
            'log': [{'severity': s, 'message': m} for s, m in log],
            'valid': not has_error,
        })
    except Exception as exc:  # noqa: BLE001
        return json.dumps({'status': 'error', 'error': _err(exc)})


def flags_from_list(flag_names, hide_flags=False):
    if isinstance(flag_names, str):
        flag_names = flag_names.split()
    """Editor-model entry point: a plain list of enabled flag names (the way
    make_react.js builds its FlagSet) → canonical string + binary + logic log.

    Mirrors FlagEditor.handleEdits → handleFlagsChanged in make_react.js:
    canonicalize with FlagSet, then run FlagLogic.fix() for the warnings/errors
    that gate generation on the site.
    """
    try:
        _ensure_env()
        from FreeEnt import flags as flagmod
        flagset = flagmod.FlagSet()
        for name in flag_names:
            flagset.set(name)
        logic = flagmod.FlagLogic()
        log = logic.fix(flagset)
        has_error = any(entry[0] == 'error' for entry in log)
        return json.dumps({
            'status': 'ok',
            'flags': flagset.to_string(),
            'binary_flags': flagset.to_binary(),
            'log': [{'severity': s, 'message': m} for s, m in log],
            'valid': not has_error,
        })
    except Exception as exc:  # noqa: BLE001
        import traceback
        return json.dumps({'status': 'error', 'error': _err(exc), 'trace': traceback.format_exc()[-1200:]})


def flag_list(flag_string):
    """Expand an existing flag string (preset, paste, share link) into the
    editor's working list of flag names — mirrors FlagSet.get_list()."""
    try:
        _ensure_env()
        from FreeEnt import flags as flagmod
        flagset = flagmod.FlagSet(flag_string)
        return json.dumps({'status': 'ok', 'list': flagset.get_list()})
    except Exception as exc:  # noqa: BLE001
        import traceback
        return json.dumps({'status': 'error', 'error': _err(exc), 'trace': traceback.format_exc()[-1200:]})


def generate(flags, seed, metaconfig_json, rom_path, out_dir, cache_dir, progress_path=None):
    """Generate a seed. Mirrors website.py's flag/seed handling.

    Returns a JSON string: {"status": "ok", ...} or {"status": "error", ...}.
    """
    try:
        _ensure_env()
        # Sanitise here rather than only in the Android UI: the desktop client and the
        # command line must accept exactly the same flag strings the app does, or a
        # flagset that works on the phone silently fails elsewhere. sanitize_flags is
        # idempotent, so a caller that already sanitised loses nothing.
        cleaned = json.loads(sanitize_flags(flags))
        if cleaned.get('status') != 'ok':
            return json.dumps(cleaned)
        flags = cleaned['flags']
        set_progress_path(progress_path)
        if progress_path:
            _install_progress_hook()
            _write_progress(1, 'Starting engine')
        return json.dumps(_generate_impl(flags, seed, metaconfig_json, rom_path, out_dir, cache_dir))
    except Exception as exc:  # noqa: BLE001 - report anything to the caller
        import traceback
        return json.dumps({
            'status': 'error',
            'error': str(exc),
            'trace': traceback.format_exc()[-2000:],
        })


def _generate_impl(flags, seed, metaconfig_json, rom_path, out_dir, cache_dir):
    import FreeEnt

    metaconfig = {}
    if metaconfig_json:
        metaconfig = json.loads(metaconfig_json)

    if seed is None:
        seed = ''
    # sanitize seed string exactly like the site
    seed = re.sub(r'[^A-Z0-9]', '', seed.upper())[:10]
    if not seed:
        seed = ''.join(random.choice(RANDOM_SEED_CHARS) for _ in range(10))

    # validates the flag string (raises on malformed input)
    FreeEnt.FlagSet(flags)

    generator = FreeEnt.Generator()
    options = generator.options
    options.flags.load(flags)
    options.seed = seed

    if metaconfig.get('hide_flags'):
        options.hide_flags = True
    if metaconfig.get('test_settings'):
        options.test_settings.update(metaconfig['test_settings'])

    # build cache: speeds up repeated generation considerably
    options.cache_path = cache_dir
    options.clean_cache = False

    with open(rom_path, 'rb') as infile:
        source_rom = io.BytesIO(infile.read())

    _write_progress(8, 'Randomizing')
    t0 = time.time()
    build_output = generator.generate(source_rom, force_recompile=False)
    elapsed = time.time() - t0
    _write_progress(99, 'Saving ROM')

    os.makedirs(out_dir, exist_ok=True)
    file_name = 'FF4FE.{}.{}.smc'.format(build_output.seed, build_output.binary_flags)
    out_path = os.path.join(out_dir, file_name)
    with open(out_path, 'wb') as outfile:
        outfile.write(build_output.rom)

    # ---- sanity checks on the finished ROM ---------------------------------
    # The pre-opened-chest table lives at bus $21D9A0 (LoROM). If it is entirely
    # 0xFF the randomizer never wrote it, and config_init.f4c would copy that
    # into $12A0, making every treasure switch read as already-looted (empty
    # chests). That was a real bug in this fork; this check makes sure a silent
    # regression is caught at generation time instead of in-game.
    warnings = []
    chests = None
    try:
        def _lorom(bus):
            return ((bus >> 16) & 0x7F) * 0x8000 + (bus & 0x7FFF)
        rom_bytes = build_output.rom
        off = _lorom(0x21D9A0)
        region = rom_bytes[off:off + 0x40]
        if len(region) == 0x40 and all(b == 0xFF for b in region):
            warnings.append(
                "Treasure table looks uninitialised (0xFF): every chest would be "
                "empty. This is a randomizer bug — do not play this ROM."
            )
        cnt = _lorom(0x21F0FA)
        if cnt + 1 < len(rom_bytes):
            chests = rom_bytes[cnt] | (rom_bytes[cnt + 1] << 8)
            if chests in (0, 0xFFFF):
                warnings.append(
                    "Chest count field looks wrong (%d) — the treasure pass may "
                    "not have run." % chests
                )
    except Exception as exc:  # noqa: BLE001
        warnings.append("sanity check failed: %s" % exc)

    _write_progress(100, 'Done')

    return {
        'status': 'ok',
        'warnings': warnings,
        'chest_count': chests,
        'path': out_path,
        'file_name': file_name,
        'seed': build_output.seed,
        'flags': build_output.flags,
        'binary_flags': build_output.binary_flags,
        'version': build_output.version,
        'verification': build_output.verification,
        'seconds': round(elapsed, 1),
        'private_spoiler': build_output.private_spoiler,
    }


# ---------------------------------------------------------------------------
# Command line
#
# The same bridge the Android app calls through Chaquopy, runnable as a process.
# The desktop build drives the engine this way (the progress file already gives the
# UI everything it needs), and it makes the engine usable from a shell or CI.
#
#     python3 fe_bridge.py version
#     python3 fe_bridge.py check --flags "Kmain"
#     python3 fe_bridge.py generate --flags Kmain --seed NAME \
#         --rom base.smc --out DIR [--cache DIR] [--progress FILE] [--ext DIR]
# ---------------------------------------------------------------------------
def _main(argv):
    import argparse
    parser = argparse.ArgumentParser(description='Free Enterprise engine bridge')
    parser.add_argument('--ext', default=None, help='directory holding FreeEnt/ and f4c/')
    sub = parser.add_subparsers(dest='command', required=True)

    sub.add_parser('version')

    check = sub.add_parser('check')
    check.add_argument('--flags', default='')

    gen = sub.add_parser('generate')
    gen.add_argument('--flags', default='')
    gen.add_argument('--seed', required=True)
    gen.add_argument('--rom', required=True, help='base FF2 (US) v1.0/v1.1 ROM')
    gen.add_argument('--out', required=True)
    gen.add_argument('--cache', default=None)
    gen.add_argument('--progress', default=None, help='file the UI polls for progress')
    gen.add_argument('--meta', default='{}')

    args = parser.parse_args(argv)
    if args.ext:
        set_ext_path(args.ext)

    if args.command == 'version':
        return engine_version()
    if args.command == 'check':
        return check_flags(args.flags)
    cache = args.cache or os.path.join(args.out, 'cache')
    return generate(
        flags=args.flags,
        seed=args.seed,
        metaconfig_json=args.meta,
        rom_path=args.rom,
        out_dir=args.out,
        cache_dir=cache,
        progress_path=args.progress,
    )


if __name__ == '__main__':
    import sys as _sys
    print(_main(_sys.argv[1:]))
