# dynamic-despawn

Immersive item despawn for Minecraft (NeoForge 1.21.1, Java 21). Mod ID:
`dynamicdespawn`. MIT licensed. See epic MINECRAFT-145, story MINECRAFT-147
(this repo's vertical slice), task MINECRAFT-151 (this implementation).

When a dropped-item stack would naturally despawn, one item from the stack
settles into the world (or is buried nearby if it can't be placed), the stack
shrinks by exactly one, and the remaining items get a fresh despawn timer. A
stack of 1 leaves nothing behind. Repeat at the next natural despawn -- one
despawn cycle handles one item.

## Current state: vertical slice (MINECRAFT-147)

One item type end-to-end, in survival, single player or multiplayer. No
breadth across item kinds, no custom rendering/animation, no config UI yet
(see MINECRAFT-145's decision comment for the full scope boundary).

## Design decisions

These were decided by the epic (MINECRAFT-145) and implemented here
(`DespawnGameplay`, `BuriedItemsSavedData`); re-opening them needs a flagged
reason, not a silent change.

1. **Ground placement.** Valid when the despawning item's own block position
   is empty and the block below it has a solid, sturdy top face (vanilla's
   usual "can something sit on top of this" check). The settled item is a
   normal `ItemEntity` -- a real world item-on-ground object, pickable like
   any other dropped item -- marked with unlimited lifetime so it does not
   itself expire, and spawned with zero velocity so it settles in place
   instead of hopping or drifting like a freshly-dropped item. No
   per-item-kind placement rules in this slice.

2. **Burial representation.** The spec calls for "a block-entity store on the
   host block." This repo implements that as a world-level persistent store
   (`BuriedItemsSavedData`, a vanilla `SavedData`) keyed by the host block's
   position, rather than a literal Minecraft block entity. A real block
   entity requires the block itself to implement `EntityBlock`, which
   arbitrary vanilla blocks (stone, dirt, etc.) do not -- forcing one would
   mean either introducing a new block type (changing world terrain where
   items are buried, which the spec does not ask for) or restricting burial
   destinations to a mod-owned block (narrowing eligibility well past "nearby
   blocks"). The `SavedData` approach keeps the host block's own type
   unchanged and satisfies the same externally-visible behavior requirement:
   breaking the host block drops the item back out (see `DespawnGameplay`'s
   `onBlockBreak`). No new UI.

3. **Burial destination.** The nearest eligible solid block (same
   solid-sturdy-top-face check as ground placement) within a fixed radius of
   **3 blocks** (Chebyshev distance) from the despawning item, already not
   hosting another buried item. Deterministic, no randomness: candidates are
   ordered by squared distance, with ties broken by ascending scan order (x,
   then y, then z) within the search cube. A candidate in a chunk that isn't
   currently loaded (checked via `Level#isLoaded`, which never forces a
   load) is skipped rather than checked -- checking it would force that
   chunk to load, which the chunk-unload safety requirement rules out.

4. **Neither placement nor burial possible.** Do nothing special --
   vanilla despawn proceeds normally -- and log it. **PROVISIONAL**, pending
   Brooswit's playtest (MINECRAFT-145's "one true team decision," not yet
   confirmed).

5. **Server/multiplayer safety.**
   - *Chunk unload mid-cycle:* burial data lives in world-level `SavedData`,
     not on a ticking block entity, so it is unaffected by the host block's
     chunk being unloaded; recovery only happens when that block is actually
     broken, which cannot occur while its chunk is unloaded anyway. The
     burial destination scan itself also never force-loads a chunk to check
     it (see point 3) -- it treats an unloaded candidate as ineligible rather
     than loading it to find out.
   - *Despawn firing mid-merge:* vanilla's own `ItemEntity.tick()` merges
     neighboring stacks before checking despawn age in the same tick, so by
     the time `ItemExpireEvent` fires, the entity's `ItemStack` already
     reflects any same-tick merge; this mod reads and mutates that stack
     once, synchronously, so merged items are never double-handled or lost.
   - *Tick cost on large drop piles:* the ground/burial check only runs once
     per item entity, at that entity's own natural despawn point (roughly
     every 6000 ticks), not on every server tick -- cost does not scale with
     how many drop piles are sitting idle.

## Known limitations

- Burial recovery only fires on a direct break of the host block
  (`BlockEvent.BreakEvent`, e.g. a player or tool breaking it). An explosion,
  piston, or other removal of the host block that does not fire that event
  loses the buried item along with the block. No recovery method besides
  breaking the host block is in scope for this slice.
- Ground placement and burial both use vanilla's "sturdy top face" check as a
  practical stand-in for "valid, solid block"; this does not special-case
  non-air replaceable blocks (tall grass, snow layers, etc.) as valid ground,
  nor liquids as invalid burial hosts beyond what that check already covers.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```
