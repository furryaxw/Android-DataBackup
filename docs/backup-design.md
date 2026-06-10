# Repository-First Backup Design

## Summary

DataBackup will move from a mixed archive/repository model to a repository-first model.

The local repository is the source of truth. Cloud storage is a synchronized copy of the same repository layout. New backups always write to the local repository first. Sync is an explicit force push or force pull between local and cloud repositories. The old archive format remains available only through a dedicated legacy entry for import or one-off restore.

## Goals

- Delete the old archive backup engine from the main backup flow.
- Store repository data directly under the selected backup directory, not under app-private `filesDir`.
- Keep local and cloud repository layouts identical.
- Make backup, sync, local restore, cloud restore, and history separate user-facing actions.
- Replace the unclear pending-upload button with a dedicated repository sync page.
- Support force push and force pull between local and cloud repositories.
- Add configurable multi-thread sync: one file per worker task, bounded by a user setting.
- Keep app backup APK and global configs outside repository data, using the current post-processing strategy.
- Keep legacy archive import/restore in a separate entry so old `tar` assumptions do not leak into the new flow.

## Non-Goals

- No bidirectional merge in the first repository sync version.
- No conflict-resolution UI beyond force push and force pull.
- No new archive backups.
- No automatic deletion of local repository files after cloud sync.
- No mixing legacy archive records into the new restore catalog.

## Glossary

- Local repository: Repository stored under the selected backup directory. This is the source of truth for new backups.
- Cloud repository: Repository stored under a configured cloud remote. Its layout matches the local repository layout.
- Repository item: One app/user backup or one file backup entry inside the repository catalog.
- Catalog: DataBackup-owned metadata files that describe repository items and restore sources.
- Rustic repository files: `config`, `data`, `index`, `snapshots`, and `keys` created by rustic.
- Force push: Copy local repository state to cloud. Local wins.
- Force pull: Copy cloud repository state to local. Cloud wins.
- Legacy archive: Old `apps/`, `files/`, `*.tar`, `*.tar.zst`, `package_restore_config.json`, and `media_restore_config.json` layout.

## Target Directory Layout

Local and cloud layouts must match.

```text
<backupDir or remote>/
  DataBackup.apk
  configs/
    configurations.json
    icon.tar
  repository/
    manifest.json
    apps/
      <packageName>/
        user_<userId>/
          databackup-item.json
          config
          data/
          index/
          snapshots/
          keys/
    files/
      DCIM/
        databackup-item.json
        config
        data/
        index/
        snapshots/
        keys/
```

`DataBackup.apk`, `configs/configurations.json`, and `configs/icon.tar` are not rustic repository data. They remain in their existing post-processing locations.

`databackup-item.json` is DataBackup metadata, not rustic metadata. It lives beside the rustic repository files so a repository item is self-contained.

## Current Structure Observed

The current repository-sync prototype produced this shape under `Z:\备份\AGM_G3`:

```text
files/
  DCIM/media_restore_config.json
  repository/files/DCIM/config/config
  repository/files/DCIM/data/<prefix>/<hash>/<hash>
```

This confirms two issues:

- Repository data and item metadata are split between unrelated trees.
- Upload target path semantics are wrong: a target directory was built as if it included the file name, then upload code appended the file name again, producing `config/config` and `<hash>/<hash>`.

The new `RepositoryLayout` module is the first fix: all code must ask one module for local paths, remote paths, and sync relative paths.

## User Flows

### Dashboard

The dashboard quick actions become:

```text
Backup
Sync
Restore from local
Restore from cloud
History
```

### Backup

Backup always writes local repository data.

Flow:

```text
Select apps/files
Run repository backup locally
Write repository item metadata
Update repository manifest
Prompt for cloud sync if a cloud account is configured
```

After backup, ask:

```text
Sync this backup to cloud?
- Sync once
- Always sync to this cloud
- Do not sync
- Cancel
```

If the user chooses always sync, persist:

```text
autoSyncAfterBackup = true
defaultSyncCloud = <cloudName>
```

### Sync

The sync page owns local-cloud repository synchronization.

It shows:

```text
Local repository path
Local item count
Local repository size
Selected cloud account
Remote repository path
Last sync state
Current sync progress
Failed files
```

Actions:

```text
Force push to cloud
Force pull from cloud
Check local repository
Check cloud repository
Retry failed sync
```

### Restore From Local

Read `<backupDir>/repository/manifest.json`, list repository items, restore from local rustic repositories.

### Restore From Cloud

Select cloud account, read `<remote>/repository/manifest.json`, list repository items, restore from cloud repository files. The first implementation may download the required cloud repository item to a local temporary restore workspace before invoking rustic restore.

### Legacy Archive

Legacy archive support is a separate entry, not part of the new restore flow.

Entry name:

```text
Legacy backups
```

Actions:

```text
Import legacy backup
Restore directly from legacy backup
```

Import converts old archive data into the new repository layout. Direct restore reads old archive data once without converting.

## Modules And Seams

### RepositoryLayout

Path-only module. It owns every path under local and cloud repositories.

Responsibilities:

- Build local repository paths from the selected backup directory.
- Build remote repository paths from a cloud remote root.
- Build app and file repository item paths.
- Build `manifest.json` and `databackup-item.json` paths.
- Convert an absolute local repository file path to a repository-relative path.
- Convert a repository-relative path to the remote parent directory expected by `CloudClient.upload(src, dstDir)`.

This module prevents repeated filename segments such as `config/config`.

### BackupCatalog

DataBackup metadata module.

Responsibilities:

- Read and write `repository/manifest.json`.
- Read and write per-item `databackup-item.json`.
- List local repository items.
- List cloud repository items after downloading or reading cloud catalog files.

### RepositoryBackupUseCase

Backup module for new backups.

Responsibilities:

- Initialize rustic item repository when missing.
- Create snapshots for selected app/file data.
- Update item metadata and manifest.
- Never upload cloud data directly.

### RepositorySyncUseCase

Synchronization module.

Responsibilities:

- Build file-level sync plans from `RepositoryLayout`.
- Execute force push and force pull.
- Skip identical files before queueing only when `repository/meta.json` v2 provides matching `relativePath`, size, and sha256.
- Upload/download through bounded concurrency.
- Keep local repository files after successful push.

### SyncQueue

Persistent sync progress module.

Responsibilities:

- Store sync tasks and sync file tasks.
- Survive process death.
- Support multi-thread workers without JSON file races.
- Surface progress to the sync page.

This should be Room-backed. The current `pending_uploads.json` is not safe enough for concurrent sync.

### RepositoryRestoreSource

Restore source module.

Adapters:

- `LocalRepositoryRestoreSource`
- `CloudRepositoryRestoreSource`

Responsibilities:

- List restore candidates from a source catalog.
- Provide a local rustic repository path for restore, either directly or through a downloaded workspace.

### LegacyArchiveImportUseCase

Legacy module that contains old archive assumptions.

Responsibilities:

- Scan legacy `apps/` and `files/` directories.
- Read `package_restore_config.json` and `media_restore_config.json`.
- Convert legacy archives into new repository items.

### LegacyArchiveRestoreUseCase

One-off restore module for old backups.

Responsibilities:

- Restore directly from old `tar` and `tar.zst` archives.
- Keep archive restore code outside the new repository restore flow.

## Sync Semantics

### Force Push

Local wins.

```text
source: <backupDir>/repository
target: <remote>/repository
```

For each source file:

- Compute repository-relative path.
- Map it to the remote parent directory.
- Compare with remote `repository/meta.json` v2 when available.
- Queue the file only when `relativePath`, size, and sha256 do not all match.
- Upload to a partial file.
- Verify size.
- Rename partial to final.
- Rewrite remote `repository/meta.json` after successful sync.

Force push does not delete local files.

### Force Pull

Cloud wins.

```text
source: <remote>/repository
target: <backupDir>/repository
```

For each remote file:

- Compute repository-relative path.
- Map it to local target path.
- Compare with local `repository/meta.json` v2 when available.
- Queue the file only when `relativePath`, size, and sha256 do not all match.
- Download to a partial local file.
- Verify size.
- Rename partial to final.

Force pull prunes local repository files that are not present in the cloud repository after the transfer succeeds. The UI must keep force pull presented as a destructive cloud-wins action.

## Multi-Thread Sync

Threading model:

```text
sync plan files -> bounded worker pool -> one file task per worker slot
```

Settings:

```text
syncConcurrency: 1..16, default 4
syncRetries: 1..10
```

Rules:

- One file task occupies one worker.
- Each worker uses its own `CloudClient` session.
- Do not share one `CloudClient` across worker threads.
- Queue updates must be transactional or mutex-protected.
- Progress is per file and aggregate.

## Settings

Backup settings after removing archive engine:

```text
Backup directory
Auto sync after backup
Default sync cloud
Ask after backup when cloud is available
Backup app APK
Backup app configs
Kill app option
Follow symlinks
Check keystore
```

Sync settings:

```text
Sync concurrency
Retry count
```

Failed sync queue behavior:

```text
Automatic retry exhausts configured retries, then keeps failed file tasks in the failed queue. Failed tasks are retried only from an explicit user retry action.
```

Removed from normal settings:

```text
Backup engine
Compression level
Compression test
Archive cache strategy
Manual pending file upload
```

## Migration Strategy

### App-Private Repository Migration

Current prototype repositories may exist under:

```text
<app filesDir>/repository
```

Migration copies or moves them to:

```text
<backupDir>/repository
```

Then writes missing `manifest.json` and item metadata.

### Legacy Archive Migration

Legacy archive import converts old backups into repository items.

Legacy direct restore remains available for users who do not want to convert large backups.

### Pending Upload Migration

Existing `pending_uploads.json` can be ignored or imported into the new sync queue as failed legacy sync tasks. New repository sync must not depend on it.

## Verification

- New local backup creates repository data under `<backupDir>/repository`.
- No new backup writes `*.tar` or `*.tar.zst` for app/file data.
- APK and configs still use existing post-processing locations.
- Sync push writes the same repository relative paths to cloud without duplicate file-name segments.
- Sync push does not delete local repository files.
- Sync pull creates the same repository layout locally.
- Multi-thread sync survives retries and process death.
- Local restore lists repository items from local catalog.
- Cloud restore lists repository items from cloud catalog.
- Legacy import converts old archive backups into repository items.
- Legacy direct restore restores old archive backups without polluting the new restore flow.
