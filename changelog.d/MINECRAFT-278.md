bump: patch

### Fixed
- Treat a rejected world write for a despawning BLOCK item as failed placement, so the item can use the existing burial fallback instead of being consumed without a block appearing.
