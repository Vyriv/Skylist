# Naming Debt Report

This repo still mixes historical `throwerlist` naming with current `Skylist` branding.

## Where debt remains

- Kotlin/Java package names:
  - `dev.ryan.throwerlist`
  - source folders still live under `src/main/kotlin/dev/ryan/shitterlist` and `src/main/java/dev/ryan/shitterlist`
- Config/data paths:
  - `config/throwerlist`
  - `throwerlist.json`
  - `scammers.json`
  - `scammer_verdicts.json`
- Resource/asset identifiers:
  - `throwerlist/...` resource paths
  - `Identifier.of("throwerlist", ...)`
- Translation/keybinding identifiers:
  - keys such as `key.throwerlist.open_gui`
- Class names:
  - `ThrowerListMod`, `ThrowerListGuiLauncher`, `ThrowerListKeybinds`

## Safe to rename later

- UI-visible class/file names that are internal only
- helper/object names that are not serialized and are not referenced by resource IDs
- command/help text where the runtime command itself stays the same

## Unsafe to rename without a migration pass

- package names referenced by mixins or Fabric entrypoints
- config directory/file names under `config/throwerlist`
- JSON/resource paths under `throwerlist/...`
- `Identifier` namespaces/paths used for textures, themes, or runtime assets
- persisted data filenames and any cache files

## Recommendation

Do behavior fixes first. Rename only after a deliberate migration pass that preserves:

- existing config/data compatibility
- mixin targets / Fabric entrypoints
- asset/resource lookup paths
