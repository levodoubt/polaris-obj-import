# Polaris Objuilder

**External 3D model importer (OBJ / glTF)** — import Blender-made buildings, machines and scenery into Minecraft, with support for animation, PBR materials, collision and right-click interaction.

> Target environment: Minecraft 1.21.1 · NeoForge 21.1.248 · Java 21

---

## Features

| Capability | Description |
|---|---|
| **Dual-format import** | `.obj` (with `.mtl`) and `.glb` (glTF 2.0 binary, native Blender export) |
| **Whole-model visual rendering** | A single entity carries the entire model; geometry = original face count (no lag even on huge models) |
| **Multi-material** | `map_Kd` / `baseColorTexture` / solid color, one draw call per material |
| **Textures** | Dynamic per-pixel texture mapping; `KHR_texture_transform` (UV scale/offset/rotation) applied correctly |
| **Emissive** | Ke visual glow + `minecraft:light` blocks for real illumination |
| **Transparency** | Alpha translucency (BLEND) + MASK cutout |
| **Animation** | glb node TRS rigid-body animation: looping + triggered playback (once/loop) + server sync |
| **PBR** | Normal / metallic / roughness under Iris shaders (labPBR) |
| **Authoritative placement** | Position + yaw + scale, SavedData persistence, restored on reload |
| **Collision** | `col:`-prefixed collider boxes → multi-AABB entity collision, enterable buildings supported |
| **Interaction** | Right-click to toggle doors (`col:xxx@AnimName`), per-part independent triggers |
| **Story integration** | Optional StoryCore integration: story script action nodes drive model animations |

---

## Installation

1. Put `polarisobjuilder-1.0.0.jar` into the game's `mods/` folder
2. Requires NeoForge 21.1.248 + Minecraft 1.21.1

**Optional dependencies** (features degrade gracefully, the mod works standalone):
- **Iris / Oculus**: enables PBR (normal/metallic/roughness) and shader support
- **polarisstorycore**: enables story action integration (`polarisobjuilder:anim`)

---

## Placing Models

Put your `.obj` / `.glb` files into:

```
config/polarisobjuilder/models/
```

References are relative to that folder (e.g. `models/building.glb`, or just the filename `building.glb`).

---

## Commands

### Placement & Management (authoritative)

```
/objplace <x y z> <yaw> <scale> <ref>    # Place a model at the given position (supports ~ relative coords)
/objlist                                  # List all placements
/objremove <id>                           # Remove a placement
/objclear                                 # Clear all placements
```

Example: `/objplace ~ ~1 ~ 0 1.0 models/door.glb` (at the player's feet, yaw=0, 1:1)

### Animation Control

```
/glbanim play <name> once|loop            # Play an animation
/glbanim stop <name>                      # Stop (hold current pose)
/glbanim reset <name>                     # Reset (back to initial pose)
```

### Quick Import (debug, not persisted)

```
/glbdomain [scale] <file>                 # glb static import (near the player)
/objdomain [scale] <file>                 # OBJ visual import
```

---

## Model Authoring Conventions (Blender)

| Naming | Meaning |
|---|---|
| `col:xxx` | Collider box (box object, not rendered, blocks only) |
| `col:xxx@AnimName` | Dynamic collider + right-click interaction (door): right-click toggles open/closed and drives the `AnimName` animation |

- Colliders are plain box objects named with the `col:` prefix, exported in the same `.glb` file as the model
- **Wall thickness ≥ 0.25 block** to prevent players clipping through at high speed
- Put boxes where you want blocking, leave gaps (doorways/interiors) empty → naturally supports enterable buildings
- Models with `col:` boxes must be placed with yaw 0/90/180/270 (colliders must stay axis-aligned)

---

## Story Integration (optional)

With StoryCore installed, story scripts can drive model animations via action nodes:

```json
{
  "id": "open_door",
  "type": "action",
  "action": "polarisobjuilder:anim",
  "actionParams": { "name": "OpenDoor", "mode": "once" }
}
```

---

## Building from Source

```bash
.\gradlew.bat build --no-configuration-cache --offline "-Dorg.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G"
```

Output: `build/libs/polarisobjuilder-1.0.0.jar`

---

## License

[GNU General Public License v3.0](LICENSE)
