# Local backup REST API

- Status: P9-001 contract; manual creation, listing, verification, and download only
- Scope: the profile that is active when each request starts
- Transport and security: the browser session, exact Host/Origin checks, CSRF rules, error envelope, and request IDs follow the [global API contract](../api.md)
- Format decision: [ADR-016](../decisions/ADR-016-local-backup-snapshot.md)

This API never accepts or returns a filesystem path. A backup is not encrypted; downloading it creates a portable copy of the user's financial data.

## Resource representation

```json
{
  "id": "9fc1df09-f1d3-4c9b-97be-f592f65d0c29",
  "profileId": "2cb615ab-5118-4479-836d-5c27c5beada7",
  "createdAt": "2026-09-24T04:30:00Z",
  "formatVersion": 3,
  "schemaVersion": 6,
  "applicationVersion": "0.1.0-SNAPSHOT",
  "dataRevision": 42,
  "counts": {
    "records": 120,
    "categories": 18,
    "accounts": 4,
    "metrics": 12
  },
  "sizeBytes": 98304,
  "integrityStatus": "VALID",
  "encrypted": false,
  "fileName": "9fc1df09-f1d3-4c9b-97be-f592f65d0c29.ledgerx-backup"
}
```

`counts` includes archived or trashed rows because those rows remain part of the recoverable ledger. `integrityStatus` is `VALID` after creation or an explicit deep verification. Listing may return `NOT_VERIFIED` for a structurally readable package or `INVALID` when its bounded manifest cannot be read; listing does not perform a database integrity scan. `fileName` is a generated UUID name, never an original local path. `encrypted=false` is deliberately explicit.

## POST /api/v1/backups

Creates one consistent snapshot of the profile active at request start.

Required headers are `Idempotency-Key: <lowercase UUID v4>` and `X-LedgerX-CSRF`. The closed request body is exactly `{}`. The idempotency key is also the backup ID. Within one profile, retrying the same key returns the one already-published and revalidated backup; a lost response therefore does not create another package. IDs are resolved only under the profile active for the current request, so an ID from another profile behaves as not found.

Success is `201 Created`, with `Location: /api/v1/backups/{id}/download`:

```json
{
  "data": { "backup": { "id": "9fc1df09-f1d3-4c9b-97be-f592f65d0c29" } },
  "meta": { "dataRevision": 42 }
}
```

The abbreviated `backup` above has the complete resource fields defined earlier. `meta.dataRevision` is the revision contained by the snapshot, not a later active-ledger revision.

The server captures the active profile ID and ledger file under the application gate, then releases the gate before invoking SQLite's online backup API. Normal ledger writes may continue during the snapshot. A concurrent profile switch does not redirect the in-flight output: the package remains under the captured profile and its manifest must match that profile.

The server writes a same-volume staging package, closes and reopens it, checks ZIP membership, SHA-256, `PRAGMA integrity_check`, and `PRAGMA foreign_key_check`, then publishes it with an atomic move. Any failure leaves the source ledger unchanged and no public backup file.

## GET /api/v1/backups?limit=25&cursor=

Lists only backups owned by the profile active when the request starts. `limit` defaults to 25 and is restricted to 1–100. `cursor` is an opaque base64url token bound to the profile and the stable descending `(createdAt,id)` order. A cursor from another profile or malformed cursor returns `400 VALIDATION_FAILED`.

```json
{
  "data": {
    "items": [],
    "page": { "nextCursor": null, "hasMore": false, "limit": 25 }
  },
  "meta": { "dataRevision": 42 }
}
```

## GET /api/v1/backups/{id}/verify

Performs a read-only deep verification. It requires no idempotency key and no CSRF token. The package is reopened and must contain exactly `manifest.json` and `ledger.db`; format/profile/schema metadata, database SHA-256, SQLite integrity, foreign keys, and manifest counts are checked.

```json
{
  "data": {
    "backup": { "id": "9fc1df09-f1d3-4c9b-97be-f592f65d0c29" },
    "verification": {
      "status": "VALID",
      "verifiedAt": "2026-09-24T04:35:00Z"
    }
  },
  "meta": { "dataRevision": 42 }
}
```

## GET /api/v1/backups/{id}/download

Streams the already-published package. Success uses `Content-Type: application/vnd.ledgerx.backup+zip`, `Content-Disposition: attachment; filename="{id}.ledgerx-backup"`, `Cache-Control: no-store`, and a fixed `Content-Length`. The server never buffers the complete package in memory and never returns its absolute path.

## Errors

| HTTP/code | Condition |
| --- | --- |
| `400 VALIDATION_FAILED` | malformed UUID/body/query, unsupported field, limit, or cursor |
| `404 NOT_FOUND` | ID is absent, malformed in the path, or belongs to another profile; these cases are intentionally indistinguishable |
| `409 LEDGER_SETUP_REQUIRED` | active profile setup has not completed |
| `422 BACKUP_INVALID` | package is truncated, has unexpected/duplicate members, a bad hash/count, an unknown format, mismatched profile/schema metadata, or a failed SQLite check |
| `423 RECOVERY_REQUIRED` | no healthy active ledger is available |
| `503 SERVICE_UNAVAILABLE` | snapshot, staging, filesystem sync, or atomic publication cannot complete |

Errors and logs never include a local absolute path, financial values, notes, category names, account names, or file contents.
