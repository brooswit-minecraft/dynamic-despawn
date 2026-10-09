bump: minor

### Fixed
- Dynamic despawn: a despawning BLOCK item (one whose item is a `BlockItem`,
  e.g. a dirt or stone block) now places its corresponding block on a valid
  ground face instead of centring the dropped item with no block appearing.
  Falls back to burial, as before, when the block can't be placed there.
  The non-BLOCK path (settle-or-bury one item per cycle, timer reset for the
  remainder, a stack of 1 leaves nothing) is unchanged.
