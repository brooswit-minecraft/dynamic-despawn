bump: minor

### Added
- Core despawn behavior: when a dropped-item stack would naturally despawn,
  one item settles onto valid ground or is buried into a nearby solid block,
  the stack shrinks by one, and the remaining items get a fresh despawn
  timer. A stack of 1 leaves nothing behind.
- Burial recovery: breaking a block that holds a buried item drops it back
  out.

### Fixed
- `neoforge.mods.toml`'s mod description no longer says items vanish over
  time -- it now describes the actual settle-or-bury behavior.
