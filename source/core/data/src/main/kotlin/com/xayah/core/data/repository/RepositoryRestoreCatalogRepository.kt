package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.data.R
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.MediaExtraInfo
import com.xayah.core.model.database.MediaIndexInfo
import com.xayah.core.model.database.MediaInfo
import com.xayah.core.model.database.MediaSnapshotInfo
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageSnapshotInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface RepositoryRestoreSource {
    suspend fun reload(onMsgUpdate: suspend (String) -> Unit): Int
}

class LocalRepositoryRestoreSource(
    private val backupDir: String,
    private val catalogRepository: RepositoryRestoreCatalogRepository,
) : RepositoryRestoreSource {
    override suspend fun reload(onMsgUpdate: suspend (String) -> Unit): Int {
        return catalogRepository.reloadFromRoot(
            backupDir = backupDir,
            cloud = "",
            restoreBackupDir = backupDir,
            onMsgUpdate = onMsgUpdate,
        )
    }
}

class CloudRepositoryRestoreSource(
    private val context: Context,
    private val cloud: CloudEntity,
    private val localBackupDir: String,
    private val cloudRepository: CloudRepository,
    private val catalogRepository: RepositoryRestoreCatalogRepository,
) : RepositoryRestoreSource {
    override suspend fun reload(onMsgUpdate: suspend (String) -> Unit): Int {
        val localRepository = RepositoryLayout.fromRoot(localBackupDir).repositoryRoot
        val remoteRepository = RepositoryLayout.fromRoot(cloud.remote).repositoryRoot
        onMsgUpdate(context.getString(R.string.repository_syncing_from_cloud).format(cloud.name))
        val result = cloudRepository.forcePullRepository(
            cloud = cloud.name,
            remoteRepositoryRoot = remoteRepository,
            localRepositoryRoot = localRepository,
        )
        if (result.isSuccess.not()) {
            onMsgUpdate(result.errors.take(3).joinToString("\n"))
            return 0
        }
        return catalogRepository.reloadFromRoot(
            backupDir = localBackupDir,
            cloud = cloud.name,
            restoreBackupDir = cloud.remote,
            onMsgUpdate = onMsgUpdate,
        )
    }
}

class RepositoryRestoreCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageDao: PackageDao,
    private val mediaDao: MediaDao,
    private val cloudRepository: CloudRepository,
    private val rootService: RemoteRootService,
) {
    private data class PackageCatalogKey(
        val packageName: String,
        val userId: Int,
        val preserveId: Long,
    )

    private data class MediaCatalogKey(
        val name: String,
        val preserveId: Long,
    )

    private val catalog = BackupCatalog(rootService = rootService)

    suspend fun localSource(): RepositoryRestoreSource =
        LocalRepositoryRestoreSource(
            backupDir = context.localBackupSaveDir(),
            catalogRepository = this,
        )

    suspend fun cloudSource(cloudName: String): RepositoryRestoreSource? {
        val cloud = cloudRepository.queryByName(cloudName) ?: return null
        return CloudRepositoryRestoreSource(
            context = context,
            cloud = cloud,
            localBackupDir = context.localBackupSaveDir(),
            cloudRepository = cloudRepository,
            catalogRepository = this,
        )
    }

    suspend fun reloadLocal(onMsgUpdate: suspend (String) -> Unit): Int {
        val backupDir = context.localBackupSaveDir()
        return reloadFromRoot(
            backupDir = backupDir,
            cloud = "",
            restoreBackupDir = backupDir,
            onMsgUpdate = onMsgUpdate,
        )
    }

    suspend fun reloadCloud(cloudName: String, onMsgUpdate: suspend (String) -> Unit): Int {
        val source = cloudSource(cloudName)
        if (source == null) {
            onMsgUpdate(context.getString(R.string.repository_cloud_account_not_found).format(cloudName))
            return 0
        }
        return source.reload(onMsgUpdate)
    }

    internal suspend fun reloadFromRoot(
        backupDir: String,
        cloud: String,
        restoreBackupDir: String,
        onMsgUpdate: suspend (String) -> Unit,
    ): Int {
        val layout = RepositoryLayout.fromRoot(backupDir)
        val items = catalog.readItemManifests(layout)
        purgeMissingRepositoryEntries(
            cloud = cloud,
            backupDir = restoreBackupDir,
            items = items,
        )
        if (items.isEmpty()) {
            onMsgUpdate(context.getString(R.string.repository_catalog_empty))
            return 0
        }

        var count = 0
        items.forEach { item ->
            val itemBytes = repositoryItemBytes(layout = layout, item = item)
            when (item.type) {
                RepositoryItemType.APP -> {
                    upsertPackageItem(item = item, cloud = cloud, backupDir = restoreBackupDir, itemBytes = itemBytes)
                    count++
                }

                RepositoryItemType.FILE -> {
                    upsertMediaItem(item = item, cloud = cloud, backupDir = restoreBackupDir, itemBytes = itemBytes)
                    count++
                }
            }
            onMsgUpdate(context.getString(R.string.repository_catalog_item_reloaded).format(item.name))
        }
        return count
    }

    private suspend fun upsertPackageItem(item: RepositoryItemManifest, cloud: String, backupDir: String, itemBytes: Long) {
        val next = item.toPackageEntity(cloud = cloud, backupDir = backupDir, itemBytes = itemBytes)
        val existing = packageDao.query(
            packageName = next.packageName,
            opType = OpType.RESTORE,
            userId = next.userId,
            cloud = cloud,
            backupDir = backupDir,
        ).filter {
            it.preserveId == next.preserveId &&
                    it.snapshotInfo.repositorySource
        }.sortedBy { it.id }
        val current = existing.sortedWith(
            compareBy<PackageEntity> { it.extraInfo.uid == -1 }
                .thenBy { it.id }
        ).firstOrNull()
        existing.map { it.id }
            .filter { it != current?.id }
            .takeIf { it.isNotEmpty() }
            ?.let { packageDao.deleteByIds(it) }
        packageDao.upsert(
            next.copy(
                id = current?.id ?: 0,
                indexInfo = next.indexInfo.copy(
                    compressionType = current?.indexInfo?.compressionType ?: next.indexInfo.compressionType,
                ),
                packageInfo = current?.packageInfo?.copy(
                    label = next.packageInfo.label.ifBlank { current.packageInfo.label },
                ) ?: next.packageInfo,
                extraInfo = next.extraInfo.copy(
                    uid = current?.extraInfo?.uid ?: next.extraInfo.uid,
                    hasKeystore = current?.extraInfo?.hasKeystore ?: next.extraInfo.hasKeystore,
                    permissions = current?.extraInfo?.permissions ?: next.extraInfo.permissions,
                    ssaid = current?.extraInfo?.ssaid ?: next.extraInfo.ssaid,
                    blocked = current?.extraInfo?.blocked ?: false,
                    activated = current?.extraInfo?.activated ?: false,
                    firstUpdated = current?.extraInfo?.firstUpdated ?: next.extraInfo.firstUpdated,
                    enabled = current?.extraInfo?.enabled ?: next.extraInfo.enabled,
                ),
            )
        )
    }

    private suspend fun upsertMediaItem(item: RepositoryItemManifest, cloud: String, backupDir: String, itemBytes: Long) {
        val next = item.toMediaEntity(cloud = cloud, backupDir = backupDir, itemBytes = itemBytes)
        val existing = mediaDao.query(
            opType = OpType.RESTORE,
            name = next.name,
            cloud = cloud,
            backupDir = backupDir,
        ).filter {
            it.preserveId == next.preserveId &&
                    it.snapshotInfo.repositorySource
        }.sortedBy { it.id }
        existing.drop(1).map { it.id }.takeIf { it.isNotEmpty() }?.let { mediaDao.delete(it) }
        val current = existing.firstOrNull()
        mediaDao.upsert(
            next.copy(
                id = current?.id ?: 0,
                indexInfo = next.indexInfo.copy(
                    compressionType = current?.indexInfo?.compressionType ?: next.indexInfo.compressionType,
                ),
                extraInfo = next.extraInfo.copy(
                    blocked = current?.extraInfo?.blocked ?: false,
                    activated = current?.extraInfo?.activated ?: false,
                ),
            )
        )
    }

    private suspend fun purgeMissingRepositoryEntries(
        cloud: String,
        backupDir: String,
        items: List<RepositoryItemManifest>,
    ) {
        purgeMissingRepositoryPackages(
            cloud = cloud,
            backupDir = backupDir,
            validKeys = items.filter { it.type == RepositoryItemType.APP }
                .map { PackageCatalogKey(packageName = it.packageName, userId = it.userId, preserveId = it.preserveId) }
                .toSet(),
        )
        purgeMissingRepositoryMedia(
            cloud = cloud,
            backupDir = backupDir,
            validKeys = items.filter { it.type == RepositoryItemType.FILE }
                .map { MediaCatalogKey(name = it.name, preserveId = it.preserveId) }
                .toSet(),
        )
    }

    private suspend fun purgeMissingRepositoryPackages(
        cloud: String,
        backupDir: String,
        validKeys: Set<PackageCatalogKey>,
    ) {
        val staleIds = packageDao.queryPackages(
            opType = OpType.RESTORE,
            cloud = cloud,
            backupDir = backupDir,
            repositorySource = true,
        ).filter {
            PackageCatalogKey(
                packageName = it.packageName,
                userId = it.userId,
                preserveId = it.preserveId,
            ) !in validKeys
        }.map { it.id }
        if (staleIds.isNotEmpty()) {
            packageDao.deleteByIds(staleIds)
        }
    }

    private suspend fun purgeMissingRepositoryMedia(
        cloud: String,
        backupDir: String,
        validKeys: Set<MediaCatalogKey>,
    ) {
        val staleIds = mediaDao.query(
            opType = OpType.RESTORE,
            cloud = cloud,
            backupDir = backupDir,
            repositorySource = true,
        ).filter {
            MediaCatalogKey(
                name = it.name,
                preserveId = it.preserveId,
            ) !in validKeys
        }.map { it.id }
        if (staleIds.isNotEmpty()) {
            mediaDao.delete(staleIds)
        }
    }

    private fun RepositoryItemManifest.toPackageEntity(cloud: String, backupDir: String, itemBytes: Long): PackageEntity {
        val sizeStats = packageDataStatsFromBytes(itemBytes)
        return PackageEntity(
            id = 0,
            indexInfo = PackageIndexInfo(
                opType = OpType.RESTORE,
                packageName = packageName,
                userId = userId,
                compressionType = CompressionType.TAR,
                preserveId = preserveId,
                cloud = cloud,
                backupDir = backupDir,
            ),
            packageInfo = PackageInfo(
                label = name,
                versionName = "",
                versionCode = 0,
                flags = 0,
                firstInstallTime = 0,
                lastUpdateTime = 0,
            ),
            extraInfo = PackageExtraInfo(
                uid = -1,
                hasKeystore = false,
                permissions = listOf(),
                ssaid = "",
                lastBackupTime = updatedAt,
                blocked = false,
                activated = false,
                firstUpdated = true,
                enabled = true,
            ),
            snapshotInfo = PackageSnapshotInfo(
                repositorySource = true,
                apkSnapshotId = snapshotIdFor(DataType.PACKAGE_APK),
                userSnapshotId = snapshotIdFor(DataType.PACKAGE_USER),
                userDeSnapshotId = snapshotIdFor(DataType.PACKAGE_USER_DE),
                dataSnapshotId = snapshotIdFor(DataType.PACKAGE_DATA),
                obbSnapshotId = snapshotIdFor(DataType.PACKAGE_OBB),
                mediaSnapshotId = snapshotIdFor(DataType.PACKAGE_MEDIA),
            ),
            dataStates = PackageDataStates(
                apkState = dataState(DataType.PACKAGE_APK),
                userState = dataState(DataType.PACKAGE_USER),
                userDeState = dataState(DataType.PACKAGE_USER_DE),
                dataState = dataState(DataType.PACKAGE_DATA),
                obbState = dataState(DataType.PACKAGE_OBB),
                mediaState = dataState(DataType.PACKAGE_MEDIA),
                permissionState = DataState.Selected,
                ssaidState = DataState.Selected,
            ),
            storageStats = PackageStorageStats(),
            dataStats = sizeStats,
            displayStats = sizeStats,
        )
    }

    private fun RepositoryItemManifest.toMediaEntity(cloud: String, backupDir: String, itemBytes: Long): MediaEntity {
        return MediaEntity(
            id = 0,
            indexInfo = MediaIndexInfo(
                opType = OpType.RESTORE,
                name = name,
                compressionType = CompressionType.TAR,
                preserveId = preserveId,
                cloud = cloud,
                backupDir = backupDir,
            ),
            mediaInfo = MediaInfo(
                path = sourcePath,
                dataBytes = itemBytes,
                displayBytes = itemBytes,
            ),
            extraInfo = MediaExtraInfo(
                lastBackupTime = updatedAt,
                blocked = false,
                activated = false,
                existed = true,
            ),
            snapshotInfo = MediaSnapshotInfo(
                repositorySource = true,
                mediaSnapshotId = snapshotIdFor(DataType.MEDIA_MEDIA),
            ),
        )
    }

    private fun RepositoryItemManifest.dataState(type: DataType): DataState {
        return if (snapshotIdFor(type).isNotBlank()) DataState.Selected else DataState.Disabled
    }

    private fun RepositoryItemManifest.snapshotIdFor(type: DataType): String = snapshots[type.name].orEmpty()

    private suspend fun repositoryItemBytes(layout: RepositoryLayout, item: RepositoryItemManifest): Long {
        val repoPath = "${layout.repositoryRoot}/${item.repositoryRelativePath}"
        return if (rootService.exists(repoPath)) rootService.calculateSize(repoPath) else 0L
    }

    private fun RepositoryItemManifest.packageDataStatsFromBytes(itemBytes: Long): PackageDataStats {
        val selectedTypes = listOf(
            DataType.PACKAGE_APK,
            DataType.PACKAGE_USER,
            DataType.PACKAGE_USER_DE,
            DataType.PACKAGE_DATA,
            DataType.PACKAGE_OBB,
            DataType.PACKAGE_MEDIA,
        ).filter { snapshotIdFor(it).isNotBlank() }
        if (selectedTypes.isEmpty()) return PackageDataStats()

        val base = itemBytes / selectedTypes.size
        var remainder = itemBytes % selectedTypes.size
        val values = linkedMapOf<DataType, Long>()
        selectedTypes.forEach { type ->
            val extra = if (remainder > 0) {
                remainder -= 1
                1L
            } else {
                0L
            }
            values[type] = base + extra
        }
        return PackageDataStats(
            apkBytes = values[DataType.PACKAGE_APK] ?: 0L,
            userBytes = values[DataType.PACKAGE_USER] ?: 0L,
            userDeBytes = values[DataType.PACKAGE_USER_DE] ?: 0L,
            dataBytes = values[DataType.PACKAGE_DATA] ?: 0L,
            obbBytes = values[DataType.PACKAGE_OBB] ?: 0L,
            mediaBytes = values[DataType.PACKAGE_MEDIA] ?: 0L,
        )
    }
}
