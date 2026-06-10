# Repository-First Implementation TODO

## Phase 0 - Documentation And Safety

- [x] Record repository-first design in `docs/backup-design.md`.
- [x] Keep old archive support isolated behind a legacy entry.
- [x] Avoid removing archive restore code until legacy direct restore exists.
- [x] Avoid deleting local repository files during current pending repository sync.

## Phase 1 - Repository Layout

- [x] Add `RepositoryLayout` path module.
- [x] Route all new repository paths through `RepositoryLayout`.
- [x] Stop building remote repository upload paths by hand for current queued repository uploads.
- [x] Fix duplicate remote filename segments such as `config/config` and `<hash>/<hash>` for current queued repository uploads.
- [x] Add compile verification for path mapping.

## Phase 2 - Repository Catalog

- [x] Define `RepositoryManifest` model.
- [x] Define `RepositoryItemManifest` model.
- [x] Add `BackupCatalog` module.
- [x] Write `repository/manifest.json` after current repository-engine backup snapshots.
- [x] Write `databackup-item.json` beside each current repository item.
- [x] Load local restore items from catalog into existing RESTORE database records.

## Phase 3 - Local Repository Backup

- [x] Move repository root from app-private `filesDir/repository` to `<backupDir>/repository` for current repository engine calls.
- [x] Update package repository paths to `repository/apps/<packageName>/user_<userId>` for current repository engine calls.
- [x] Update file repository paths to `repository/files/<name>` for current repository engine calls.
- [x] Keep `DataBackup.apk` post-processing unchanged.
- [x] Keep `configs/configurations.json` post-processing unchanged.
- [x] Keep `configs/icon.tar` until icon handling is redesigned.
- [x] Remove cloud staging from backup services.
- [x] Prompt for cloud sync after local backup when cloud is available.
- [x] Add "remember this choice" behavior for post-backup sync.

## Phase 4 - Sync Queue

- [x] Add Room entity for sync tasks.
- [x] Add Room entity for sync file tasks.
- [x] Add DAO for claiming pending file tasks safely.
- [x] Migrate or retire `pending_uploads.json`.
- [x] Add sync state repository.

## Phase 5 - Sync Page

- [x] Add `MainRoutes.Sync`.
- [x] Add sync feature/page.
- [x] Show local repository status.
- [x] Show selected cloud repository status.
- [x] Add force push action.
- [x] Add force pull action.
- [x] Add retry failed sync action.
- [x] Add per-file progress and aggregate progress.
- [x] Add error list.

## Phase 6 - Multi-Thread Sync

- [x] Add sync concurrency setting, default 4.
- [x] Add retry setting reuse or dedicated sync retry setting.
- [x] Align sync concurrency setting and runtime bounds to 1..16.
- [x] Implement bounded worker pool.
- [x] Use one `CloudClient` per worker.
- [x] Upload one file per worker task.
- [x] Download one file per worker task.
- [ ] Verify partial file rename behavior for FTP, SFTP, SMB, and WebDAV.
- [x] Ensure sync queue task/file creation is transaction-safe.

## Phase 6a - Repository Metadata And Transfer Planning

- [x] Read repository state from `repository/meta.json` before falling back to remote scans.
- [x] Write `meta.json` version 2 with per-file sha256 hashes.
- [x] Treat missing, invalid, or incompatible remote meta as a reason to scan and rebuild.
- [x] Filter force-push upload plans by `relativePath + bytes + sha256` before queueing.
- [x] Filter force-pull download plans by `relativePath + bytes + sha256` before queueing.
- [x] Keep repository total stats separate from this-run transfer stats.
- [x] Disable unsafe same-size-only skip for normal and pending single-file uploads.
- [x] Actively disconnect cloud clients when repository sync is paused or cancelled.
- [x] Keep failed sync file tasks after automatic retries for explicit manual retry.

## Phase 7 - Restore Split

- [x] Replace dashboard restore action with local restore and cloud restore actions.
- [x] Add `LocalRepositoryRestoreSource`.
- [x] Add `CloudRepositoryRestoreSource`.
- [x] Local restore lists catalog entries from `<backupDir>/repository`.
- [x] Cloud restore selects cloud account and lists catalog entries from `<remote>/repository`.
- [x] Restore uses explicit snapshot metadata instead of hard-coded `latest` when catalog support exists.

## Phase 8 - Legacy Entry

- [x] Add legacy backups route/page.
- [x] Detect legacy archive directory structure.
- [x] Show legacy app/file counts.
- [x] Implement import legacy backup to repository.
- [x] Implement direct restore from legacy backup.
- [ ] Keep legacy tar/tar.zst code inside legacy modules.

## Phase 9 - Remove Archive Engine From Main Flow

- [x] Remove backup engine setting.
- [x] Remove compression level from normal backup settings.
- [x] Remove compression test from normal backup settings.
- [x] Remove archive backup calls from package backup flow.
- [x] Remove archive backup calls from file backup flow.
- [x] Remove archive restore calls from new restore flow.
- [ ] Keep archive code only in legacy modules until final cleanup.

## Phase 10 - Verification

- [x] Run `./gradlew :core:data:compileDebugKotlin`.
- [x] Run `./gradlew :core:database:compileDebugKotlin`.
- [x] Run `./gradlew :core:service:compileDebugKotlin` after removing cloud backup services.
- [x] Run `./gradlew :feature:main:processing:compileDebugKotlin` after post-backup sync changes.
- [x] Run `./gradlew :feature:main:cloud:compileDebugKotlin`.
- [x] Run `./gradlew :feature:main:settings:compileDebugKotlin` after settings changes.
- [x] Run `./gradlew :app:assembleDebug` after route/UI changes.
- [x] Run `./gradlew lint test` before major merge.
- [ ] Manually verify local backup directory layout.
- [ ] Manually verify force push creates matching cloud layout.
- [ ] Manually verify force pull recreates local layout.
- [ ] Manually verify pause/cancel interruption during a large FTP, SFTP, SMB, and WebDAV transfer.
- [ ] Manually verify legacy direct restore.
- [ ] Manually verify legacy import then new restore.
