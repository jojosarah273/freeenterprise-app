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


def check_flags(flag_string):
    """Canonicalize a flag string AND run the logic checks in one call — used by
    the Generate screen to show the current flagset and its validity."""
    try:
        _ensure_env()
        from FreeEnt import flags as flagmod
        flagset = flagmod.FlagSet(flag_string)
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