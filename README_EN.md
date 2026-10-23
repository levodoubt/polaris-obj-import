# Obj Importer

> [简体中文](README.md) | English

**External 3D model importer (OBJ / glTF)** — bring Blender-made buildings, machines and scenery into Minecraft: whole-model rendering, PBR materials, multi-AABB collision, right-click interaction and command hooks.

> Target environment: Minecraft 1.21.1 · NeoForge 21.1.248 · Java 21 · Current version **0.5.0**

---

## Features

| Capability | Description |
|---|---|
| **Dual-format import** | `.obj` (with `.mtl`) and `.glb` (glTF 2.0 binary, native Blender export) |
| **Whole-model rendering** | One entity carries the entire model, geometry = original face count; static models are drawn from a GPU-resident VBO (no per-frame CPU vertex submission) |
| **Multi-material** | `map_Kd` / `baseColorTexture` / solid color, one draw call per material |
| **Textures** | Dynamic per-pixel texture mapping; `KHR_texture_transform` (UV scale/offset/rotation) applied correctly |
| **Emissive & lighting** | `Ke` visual glow + `light:` marker boxes that fill `minecraft:light` blocks (per-box light level 1–15) |
| **Transparency** | Alpha translucency (BLEND, depth-writing) + MASK cutout |
| **Animation** | glb node TRS rigid-body animation: looping and triggered playback (once / loop), broadcast from the server; **animation state is per placement** (identical doors stay independent) |
| **PBR** | Normal / metallic / roughness under Iris shaders (labPBR encoding) |
| **Authoritative placement** | Position + yaw + scale, persisted in world SavedData and restored on reload |
| **Collision** | `col:` marker boxes → multi-AABB entity collision; enterable buildings supported |
| **Right-click interaction** | `col:x@Anim` toggles doors; `use:x` is an interaction-only box (never blocks); `$cmdKey` runs a real command (`give`, scoreboard, story, …) |
| **Click feel** | Client-side forgiving pick + two-tier priority (smallest un-inflated hit wins for precise aiming, otherwise nearest inflated hit), bypassing vanilla's 3-block entity interaction range |
| **Self-check & self-heal** | Placement entities are reconciled on world load, player login and periodically: leftovers and duplicates are cleaned up, missing parts are re-created; failed interactions report a concrete reason |
| **Debug switch** | `/objdebug` silences every debug output of this mod (console logs, on-screen hints, chat receipts); `/objdebug feedback` controls vanilla feedback of commands run by this mod |
| **Performance** | GPU-resident VBO drawing for static models; shadow pass with sub-divided LOD, dedicated buffers and single-face CULL keeps huge models smooth |

---

## Installation

1. Put `obj_importer-<version>.jar` (e.g. `obj_importer-0.5.0.jar`) into the game's `mods/` folder
2. Requires NeoForge 21.1.248 + Minecraft 1.21.1

**Optional dependencies** (features degrade gracefully, the mod works standalone):

- **Iris / Oculus**: enables PBR (normal / metallic / roughness) and shader support; without it the mod falls back to vanilla lighting

---

## Placing Models

Put your `.obj` / `.glb` files into:

```
config/obj_importer/models/
```

References are relative to that folder (e.g. `models/building.glb`, or just the filename `building.glb`).

---

## Commands

### Placement & management (authoritative, persisted)

```
/objplace <x y z> <yaw> <scale> <ref>    # Place a model (supports ~ relative coordinates)
/objlist                                  # List all placements (id / ref / position / yaw / scale)
/objremove <id>                           # Remove a placement (colliders, light blocks and client caches go with it)
/objclear                                 # Clear all placements
```

Example: `/objplace ~ ~1 ~ 0 1.0 models/door.glb` (at the player's feet, yaw=0, 1:1)

> If the model contains `col:` boxes, yaw must be a multiple of 90° (0/90/180/270), otherwise colliders cannot stay axis-aligned with the visuals and the command is rejected.

### Command hooks (`$cmdKey` → real commands)

A marker box whose name carries `$key` runs the matching command from `config/obj_importer/commands.json` on right-click:

```json
{
  "commands": {
    "gun_take": {
      "cmds": [
        "execute if score %player% basement_gun matches 1 run give %player% tacz:modern_kinetic_gun[...] 1",
        "objremove %placement%"
      ],
      "consume": "result",
      "feedback": false
    },
    "lamp_toggle": { "cmd": "scoreboard players add %player% lamps 1", "consume": "never" }
  }
}
```

| Field | Meaning |
|---|---|
| `cmd` / `cmds` | One command / an array of commands (run in order; **if any one does not take effect the rest are aborted**) |
| `perm` | Permission level used to run it, default 2 |
| `consume` | `result` (default: rolled back and clickable again if the command did not take effect) · `always` (consumed on click) · `never` (repeatable) |
| `feedback` | Whether to keep the command's own vanilla feedback (e.g. `give`'s "Gave … to …"), default false |

Placeholders: `%player%` `%uuid%` `%part%` `%placement%` `%x%` `%y%` `%z%`

```
/objcmd reload                            # Re-read commands.json (no restart needed)
/objcmd list                              # List all command keys
/objcmd test <key>                        # Dry-run once on yourself (no consumption, no state change)
```

### Animation control

```
/glbanim play <name> once|loop            # Play an animation (once / loop)
/glbanim stop <name>                      # Stop (hold the current pose)
/glbanim reset <name>                     # Reset to the initial pose (collision state resets too)
```

### Debug

```
/objdebug                                 # Show the state of the debug-output and command-feedback switches
/objdebug true|false|on|off               # Master debug switch (false = hide all debug output)
/objdebug feedback true|false|on|off      # Whether commands run by this mod (give, …) keep vanilla feedback
```

- With `false`, three channels go silent: console diagnostics, on-screen actionbar hints, and chat receipts prefixed with `[Objuilder]` / `[Glb]`
- Not affected: `error`-level reports (including stack traces) and un-prefixed command failure messages
- Both switches are persisted in `config/obj_importer-common.toml` and can be edited directly

### Quick import (debug only, not persisted)

```
/glbdomain [scale] <file>                 # Static glb import near the player
/objdomain [scale] <file>                 # OBJ visual import
```

---

## Model Authoring Conventions (Blender)

A marker box is a plain box object named `<prefix>:<part>[@<anim>][$<cmdKey>]`, exported in the same `.glb` as the model.

| Naming | Meaning |
|---|---|
| `col:xxx` | Collider: participates in entity collision (blocks players/mobs), not rendered |
| `col:xxx@Anim` | Collider + right-click toggle: right-click switches open/closed and drives that animation; while open, collision is lifted |
| `use:xxx` | Interaction-only box: right-clickable but never blocks (props, items, buttons) |
| `use:xxx$cmdKey` | Interaction box + runs the matching command from `commands.json` |
| `light:xxx` | Light box: fills `minecraft:light` blocks inside the box (default level 15 = full brightness) |
| `light:xxx@level` | Explicit light level 1–15 (shrink the lit radius to avoid light bleeding through walls) |

- **Wall thickness ≥ 0.25 block** to prevent players clipping through at high speed
- Put `col:` boxes where you want blocking and leave gaps (doorways / interiors) empty → enterable buildings come for free
- `$` must come after `@` (`col:a@door$open`); writing `col:a$open@door` is flagged as a wrong order
- Light boxes only fill **air** blocks, are capped at 512 cells, and the excess is truncated with a warning

---

## Interaction & click feel

- Vanilla client picking is limited to 3 blocks and is hostile to thin boxes; this mod decides on the client instead:
  **forgiving inflation** (`min(0.6, 0.25 + volume×0.35)`) plus **two-tier priority** (the smallest un-inflated hit wins so precise aiming picks the exact part; otherwise the nearest inflated hit is used so near-misses still land)
- A hit is sent to the server through a custom payload, validated server-side with **4.5 blocks + a 3-tick debounce**, and then reuses the very same interaction logic
- Misses, rejections and ineffective commands all report a concrete reason (silent while `/objdebug false`)

---

## Persistence & self-healing

- Placements are stored in world `SavedData` (`obj_importer_placements`); each placement = 1 visual entity + N collider entities
- On world load, player login and every 5 seconds a **reconciliation** runs: leftovers (entities whose record is gone) and duplicate copies are removed, missing parts are re-created only once that chunk is loaded, and entity UUIDs are written back into the records
- `/objremove` and `/objclear` are idempotent: even when the record is already gone they still clean up entities by placement id, so "record gone, model still there, still clickable" cannot happen

---

## Building from Source

```bash
.\gradlew.bat build --no-configuration-cache --offline "-Dorg.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G"
```

Output: `build/libs/obj_importer-<version>.jar`

---

## References

This mod references the following open-source projects during development:

| Project | What was referenced |
|---|---|
| [MCglTF](https://github.com/ModularMods/MCglTF) | glTF animation system (node TRS rigid / skeletal / morph targets mesh animation), PBR material vertex-color handling |
| [MCObj](https://github.com/tom5454/MCObj) | OBJ / MTL parsing and import flow |

---

## License

[MIT License](LICENSE)
