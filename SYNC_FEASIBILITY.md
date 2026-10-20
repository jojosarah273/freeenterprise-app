# Multi-device sync: feasibility

Status: **researched, not planned.** No server, no account, no cost. This document
exists so the findings are not lost if we pick it up later.

The question that started this: *gen1recomp syncs saves, boxes and trainer-ID data
across devices without accounts, using just a series of numbers — could Free
Enterprise sync seed metadata the same way?*

## What gen1recomp actually does

`bryanthaboi/gen1recomp` (Lua/LÖVE2D). Client is public under `src/sync/`
(`SyncClient.lua`, `SyncEngine.lua` — 1,484 lines of policy, `SyncBox.lua`,
`SyncState.lua`). The **server is not in the repo**, and no third-party cloud is
referenced anywhere; the only origin is:

```lua
SyncClient.DEFAULT_URL = os.getenv("POKEPORT_SYNC_URL")
  or "https://sync.147.182.215.255.sslip.io"      -- src/sync/SyncClient.lua:8
```

That is a VPS they own, with TLS from sslip.io wildcard DNS (a bare IP needs no
domain). So: self-hosted, one origin, someone else's machine.

### The "series of numbers"

Two independent 8-digit codes, shown as `XXXX-XXXX`. Joining needs **both**:

```lua
POST /sync/create   { device }                -- -> code1, code2, device token
POST /sync/link     { code1, code2, device }  -- no auth required
POST /sync/codes                              -- re-issue / rotate
POST /sync/unlink   { device }
```

UI hint: *"Enter the two codes the other device is showing."* The 16-digit pair
**is** the credential — that is the entire access control. After linking, a
per-device token authenticates later calls so the codes are not resent.

### Storage

```lua
GET    /sync/state                        -- revisions only
PUT    /sync/save  { version, slot, meta, blob, baseRev, force }
GET    /sync/save  ?version=&id=
DELETE /sync/save  ?version=&id=&rev=
PUT/GET /sync/box                         -- ONE blob <= 32 MB, ?include=key,key
PUT/GET /sync/mods                        -- mod manifest
POST/GET /sync/modshare                   -- 6-character share codes
POST   /sync/displayname
POST   /lobby/ticket                      -- online play, separate concern
```

A save is keyed by **`playthroughId`**, not filename, which is what lets a new
device place a synced save into a fresh local slot and keep that mapping. `meta`
is display-only (name, badges, dex count, playtime, timestamps, engine, modCount).
Limits: 2 MB per save, 64 MB body, 32 MB box. Auto-sync every **300 s**.

### Conflict policy

It avoids conflicts before it asks:

| Situation | Behaviour |
| --- | --- |
| Identical contents (timestamps stripped first) | reconcile silently |
| Same playtime | not a conflict |
| Session ranges overlap (played both devices at once) | surfaced as `overlap`, not guessed at |
| Genuinely divergent | **Keep this device / Keep the other device / Keep both** (as a new slot) |

The rejected copy is kept in a recovery backup and never discarded. The Box is
atomic with its saves (*"Choose one complete Box collection and its linked
saves"*). Mods travel too, so the **environment** is reproduced, not just data.
And there is a compatibility gate worth copying: a save belonging to a "sealed
cart" you have not installed is refused — *"install the X cart to receive its
save"* — the closest analogue to checking that flags and engine match.

The "ID data" is `src/core/TrainerIdSync.lua`: it rebuilds owner records
(trainer ID + OT) from save and Box so Pokémon keep correct attribution across
devices. The identity layer that makes synced data belong to one playthrough.

### Security gap

**No client-side encryption.** `src/sync/` and `src/net/` contain no
encrypt/aes/nacl/hmac of any kind. The server operator can read every save. The
codes are the only protection, and they are rotatable and revocable.

## Why this is easier for us than it is for them

They sync megabytes of save, Box and mod data because that state is
irreproducible. **Free Enterprise seeds are reproducible**, so almost nothing
needs to travel. Verified on the host — same seed and flags, two separate
cold-cache runs:

```
[device A]              sha256 94bd976a19cf63a1...  verification=(bow, star, sword, harp)
[device B (cold cache)] sha256 94bd976a19cf63a1...  verification=(bow, star, sword, harp)
[byte-identical ROM: True]   [file name identical: True]   [verification identical: True]
```

So:

- The payload is **~300 bytes** of metadata, plus ~8 KB if progress travels.
- **The ROM never leaves the device** — same stance as their ROM requirement.
  Each instance regenerates from its own base ROM.
- `verification` is a free integrity check: `bow star sword harp` proves both
  devices built the same ROM without transferring it. The ROM filename already
  encodes seed + flags (`FF4FE.<seed>.<flags>.smc`).
- The key-item list the tracker needs is **recomputed by regenerating**, so it
  needs no syncing and no spoiler file.

## Cost

| Option | Money | Attention |
| --- | --- | --- |
| Sync the `FF4_FE` folder with a tool you already have | $0 | none |
| Share a seed code by text/QR | $0 | none |
| LAN direct device-to-device (their in-game link is LAN-only) | $0 | none |
| Cloudflare Worker + D1 | $0 on the free tier | deploy once, occasionally check |
| Oracle Always Free ARM VM | $0 (card required) | you patch and back it up |
| Small VPS, their model | ~$5/month | you patch and back it up |

Every option can be free. The VPS route is the only one that costs money, and it
is also the one that costs the most attention.

## The zero-cost path, if this is ever wanted

There may be no reason to build a sync service at all:

1. **Seed metadata is already self-contained.** `<name>.json` holds flags, seed,
   engine version, verification and the key-item list, and the `.srm` sits beside
   it in `FF4_FE/`. Anything that copies that folder — Syncthing, Google Drive, a
   USB cable, an SD card — already replicates a complete setup.
2. **The missing piece is rebuilding the ROM from metadata.** There is currently
   no one-tap "rebuild this ROM from its own metadata", which is all that is
   needed: the base ROM is local, and regeneration is verified byte-identical.
   This is worth doing regardless of sync, because it also recovers a seed whose
   ROM was deleted or corrupted.
3. **A seed is a short string.** `flags + seed` is a few dozen characters, so a
   "seed code" can travel down any existing channel — a text message, a note, a
   QR code — with no server involved. Progress would not travel with it, but the
   seed would be reproduced exactly.

Only if progress must follow across devices *automatically* does a server earn
its place, and then the cheapest honest version is a Cloudflare Worker holding
ciphertext keyed by the user's own code — strictly better than gen1recomp, whose
server can read everything.

## Open decisions

- Whether cross-device *progress* matters, or only reproducing the seed.
- Whether a server is acceptable at all, versus LAN-only sync between devices the
  same person owns.
- If a server: encrypt with the code (PBKDF2 + AES-GCM) so it stores ciphertext,
  rather than storing plaintext the way gen1recomp does.
