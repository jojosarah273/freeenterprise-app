"""Apply and verify the locked Golbez reference data.

The single source of truth for every locked region is
``golbez_reference/LOCKED_MANIFEST.json`` at the repository root.
Nothing in this module hardcodes blob addresses; if the manifest changes,
generation and verification change with it automatically.

``apply(rom_mutable)`` writes every locked blob into the generated ROM,
sets the map palette selector, and then verifies that the vanilla
tables/timers that Golbez relies on were left untouched. It raises
``RuntimeError`` on any mismatch, failing the build before a bad ROM
can be produced.
"""
import hashlib
import json
import os

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REFERENCE_DIR = os.path.join(_ROOT, 'golbez_reference')
MANIFEST_PATH = os.path.join(REFERENCE_DIR, 'LOCKED_MANIFEST.json')

# Map palette selector: must always be set for the Golbez field sprite.
MAP_PALETTE_SELECTOR_ADDRESS = 0x0AB307
MAP_PALETTE_SELECTOR_VALUE = 0x03

# Axtor -> actor table. character_expansion.f4c patches it at bus $21F700, one byte
# per axtor (0x18 entries). Every axtor must resolve to the Golbez character slot:
# a duplicate party member otherwise resolves to its own actor and takes THAT actor's
# graphics - a different battle sheet, a different palette, and the "mini" NPC field
# sprite - even though the locked character data already gives every actor Golbez's
# stats and properties.
AXTOR_TABLE_ADDRESS = 0x10F700
AXTOR_TABLE_LENGTH = 0x18
GOLBEZ_ACTOR = 0x13


def load_manifest():
    with open(MANIFEST_PATH, 'r') as infile:
        return json.load(infile)


def _read_blob(name):
    with open(os.path.join(REFERENCE_DIR, name), 'rb') as infile:
        return infile.read()


def _verify_blob_file(name, meta):
    data = _read_blob(name)
    if len(data) != meta['size']:
        raise RuntimeError(
            f"Golbez lock failed: reference file {name} has size {len(data)}, expected {meta['size']}")
    if hashlib.sha256(data).hexdigest() != meta['sha256']:
        raise RuntimeError(
            f"Golbez lock failed: reference file {name} does not match LOCKED_MANIFEST.json")
    return data


def apply(rom_mutable):
    """Write all locked Golbez data into ``rom_mutable`` and verify.

    Called by the generator after all randomizer patches are applied,
    so locked data always wins. Also verifies the locked vanilla regions
    afterwards and raises ``RuntimeError`` if any of them was changed.
    """
    manifest = load_manifest()

    # 1. Write every locked blob (blob data wins over any randomizer patch).
    for name, meta in manifest['blobs'].items():
        data = _verify_blob_file(name, meta)
        start = meta['address']
        rom_mutable[start:start + len(data)] = data
        if rom_mutable[start:start + len(data)] != data:
            raise RuntimeError(f"Golbez lock failed: {name} at 0x{start:06X}")

    # 2. Map palette selector for the field sprite.
    rom_mutable[MAP_PALETTE_SELECTOR_ADDRESS] = MAP_PALETTE_SELECTOR_VALUE

    # 2b. Every axtor resolves to the Golbez actor, so duplicates are Golbez too.
    for i in range(AXTOR_TABLE_LENGTH):
        rom_mutable[AXTOR_TABLE_ADDRESS + i] = GOLBEZ_ACTOR
    if any(rom_mutable[AXTOR_TABLE_ADDRESS + i] != GOLBEZ_ACTOR
           for i in range(AXTOR_TABLE_LENGTH)):
        raise RuntimeError("Golbez lock failed: axtor table not fully remapped")

    # 3. Vanilla regions Golbez depends on must remain untouched.
    for name, meta in manifest['locked_vanilla'].items():
        data = _read_blob(name)
        start = meta['address']
        if rom_mutable[start:start + len(data)] != data:
            raise RuntimeError(f"Golbez lock failed: {name} changed at 0x{start:06X}")


def blob_list():
    """Return [(name, address, data)] for every locked blob.

    Used by repair tooling that re-applies locked data to an existing ROM.
    """
    manifest = load_manifest()
    return [
        (name, meta['address'], _verify_blob_file(name, meta))
        for name, meta in manifest['blobs'].items()
        ]
