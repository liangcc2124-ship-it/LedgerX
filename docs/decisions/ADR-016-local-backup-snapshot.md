# ADR-016: Use versioned local packages built with SQLite online backup

- Status: accepted
- Date: 2026-09-24

## Context

LedgerX is a single-user Windows application served from one loopback-only Java 11 process. Java is the sole SQLite writer, but ordinary writes should not be held behind a long application-wide lock while a user creates a backup. Copying an active `ledger.db` can omit journal state and is not a valid backup. P9-001 needs manual backup only; restore, upload/import, automatic schedules, retention deletion, encryption, cloud transfer, and release packaging remain separate work.

## Decision

Use the existing Xerial sqlite-jdbc SQLite online backup capability to snapshot the ledger captured from the profile active at request start. The driver documents its online backup call in [Xerial sqlite-jdbc usage](https://github.com/xerial/sqlite-jdbc/blob/master/USAGE.md); SQLite documents consistent online snapshots and source locking in its [Backup API guide](https://www.sqlite.org/backup.html). Hold the existing profile gate only long enough to capture an immutable `(profileId, ledgerFile, schemaVersion, dataRevision)` context, then release it. SQLite coordinates concurrent source writes while the snapshot is made. Profile switching may proceed; the in-flight backup always publishes under the captured profile.

Package each snapshot as format 3, extension `.ledgerx-backup`, containing exactly `manifest.json` and `ledger.db`. The manifest records the backup/profile IDs, creation time, application/schema versions, data revision, bounded row counts, explicit `encrypted:false`, and the database SHA-256. Write and validate in a staging directory under `Backups/<profileId>/`; after closing all handles and passing SHA-256, `integrity_check`, `foreign_key_check`, metadata, and count validation, publish using a same-filesystem atomic move.

Use the required UUID v4 `Idempotency-Key` as the backup ID. The deterministic final filename provides durable retry recognition without adding a schema table. All list, verify, and download lookups are rooted in the current profile directory and never search other profiles.

## Alternatives considered

- `Files.copy` of the active database: rejected because it is not a SQLite-consistent online snapshot.
- A long application write lock around file copying or packaging: rejected because it needlessly blocks normal bookkeeping and still duplicates SQLite's consistency mechanism.
- A new backup metadata table: rejected for manual backup because the published package is the durable fact and the idempotency key maps directly to its filename.
- `VACUUM INTO`: viable for a compact consistent copy, but the existing driver exposes SQLite's purpose-built online backup capability and avoids changing page layout as a side effect of every backup.
- Recovery/import/automatic retention in the same change: rejected because each adds destructive lifecycle and compatibility decisions not needed to make a reliable snapshot.

## Consequences

Manual backups remain local and unencrypted, relying on Windows account and disk protections. Creation can coexist with normal writes; the resulting database represents one SQLite-consistent point in time. A request that races with profile activation may complete for the profile that was active when it began, and the response names that captured profile explicitly. Atomic move support on the backup volume is mandatory; the implementation fails closed instead of exposing a partially published package.

The format is forward-versioned. This version reads only format 3 and rejects unknown versions rather than guessing. A later restore feature may consume these packages, but P9-001 does not prove restoration orchestration or compatibility with the legacy C# application.
