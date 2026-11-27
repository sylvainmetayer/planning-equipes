---
twin: installer/passer-a-la-4-0
---

# Moving from 3.x to 4.0

You run a 3.x instance and want to move to 4.0. This page says, change by change, what 4.0.0 breaks compared with 3.5 and what you have to do.

## Where things stand

- **4.0.0** is published. The `3.x` line now receives only fixes, on the `release/3.5` maintenance branch ([how it is run](publier-une-version.md#3-patcher-une-version-antérieure)).
- The 4.0 breaking changes were tracked in tickets [#271](https://github.com/sylvainmetayer/planning-equipes/issues/271) (model), [#272](https://github.com/sylvainmetayer/planning-equipes/issues/272) (freeze, versions, settings), [#273](https://github.com/sylvainmetayer/planning-equipes/issues/273) (rules), [#274](https://github.com/sylvainmetayer/planning-equipes/issues/274) (MCP and screens) and [#281](https://github.com/sylvainmetayer/planning-equipes/issues/281) (constraint batches).
- How long 3.x stays supported: see [the maintenance policy](versioning.md#combien-de-temps-une-ligne-est-corrigée).

## Before upgrading

1. Stay on the latest 3.x and read its release notes: a 3.x release may already warn about what 4.0 removes.
2. Make a [backup](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main). The schema only moves forward: a database migrated to 4.0 does not go back under a 3.x image.
3. Read the "⚠️ Attention" entry of the 4.0 release notes, then the sections below.

## What changes

Each paragraph says *before*, *after* and *what you do*.
