package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.data.R
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.MediaSnapshotInfo
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.suffixOf
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.ConfigsMediaRestoreName
import com.xayah.core.util.ConfigsPackageRestoreName
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.filesDir
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class LegacyBackupSummary(
    val appsCount: Int = 0,
    val filesCount: Int = 0,
    val has10xLayout: Boolean = false,
    val has11xLayout: Boolean = false,
    val has12xLayout: Boolean = false,
) {
    val hasAny: Boolean
        get() = appsCount > 0 || filesCount > 0 || has10xLayout || has11xLayout || has12xLayout
}

data class LegacyReloadResult(
    val appsCount: Int,
    val filesCount: Int,
)

data class LegacyImportResult(
    val appSnapshots: Int,
    val fileSnapshots: Int,
    val errors: List<String>,
) {
    val isSuccess: Boolean
        get() = errors.isEmpty()
}

class LegacyBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val pathUtil: PathUtil,
    private val packageRepository: PackageRepository,
    private val mediaRepository: MediaRepository,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val backupEngineRepository: BackupEngineRepository,
) {
    private val packageArchiveNames = setOf(
        DataType.PACKAGE_APK.type,
        DataType.PACKAGE_USER.type,
        DataType.PACKAGE_USER_DE.type,
        DataType.PACKAGE_DATA.type,
        DataType.PACKAGE_OBB.type,
        DataType.PACKAGE_MEDIA.type,
    )
    private val importPackageTypes = listOf(
        DataType.PACKAGE_USER,
        DataType.PACKAGE_USER_DE,
        DataType.PACKAGE_DATA,
        DataType.PACKAGE_OBB,
        DataType.PACKAGE_MEDIA,
    )

    suspend fun detectLocal(): LegacyBackupSummary {
        val count10xApps = countLocal10xApps()
        val count10xFiles = countLocal10xFiles()
        val count11xApps = countLocal11xApps()
        val count11xFiles = countLocal11xFiles()
        val count12xApps = countLocal12xApps()
        val count12xFiles = countLocal12xFiles()
        return LegacyBackupSummary(
            appsCount = count10xApps + count11xApps + count12xApps,
            filesCount = count10xFiles + count11xFiles + count12xFiles,
            has10xLayout = count10xApps > 0 || count10xFiles > 0,
            has11xLayout = count11xApps > 0 || count11xFiles > 0,
            has12xLayout = count12xApps > 0 || count12xFiles > 0,
        )
    }

    suspend fun reloadLocal(onMsgUpdate: suspend (String) -> Unit): LegacyReloadResult {
        val summary = detectLocal()
        if (summary.has10xLayout) {
            packageRepository.modifyAppsStructureFromLocal10x(onMsgUpdate)
            packageRepository.modifyFilesStructureFromLocal10x(onMsgUpdate)
        }
        if (summary.has11xLayout) {
            packageRepository.modifyAppsStructureFromLocal11x(onMsgUpdate)
            packageRepository.modifyFilesStructureFromLocal11x(onMsgUpdate)
        }

        packageRepository.reloadAppsFromLocal12x(onMsgUpdate)
        packageRepository.reloadFilesFromLocal12x(onMsgUpdate)
        appsRepo.load(null) { cur, max, content -> onMsgUpdate("$content ($cur/$max)") }
        filesRepo.load(null) { cur, max, content -> onMsgUpdate("$content ($cur/$max)") }

        val backupDir = context.localBackupSaveDir()
        return LegacyReloadResult(
            appsCount = packageRepository.queryPackages(OpType.RESTORE, "", backupDir, repositorySource = false).size,
            filesCount = mediaRepository.query(OpType.RESTORE, "", backupDir, repositorySource = false).size,
        )
    }

    suspend fun importLocalToRepository(onMsgUpdate: suspend (String) -> Unit): LegacyImportResult {
        if (backupEngineRepository.isRepositoryEngineAvailable().not()) {
            return LegacyImportResult(
                appSnapshots = 0,
                fileSnapshots = 0,
                errors = listOf(context.getString(R.string.repository_engine_not_available)),
            )
        }

        reloadLocal(onMsgUpdate)
        val backupDir = context.localBackupSaveDir()
        val errors = mutableListOf<String>()
        var appSnapshots = 0
        var fileSnapshots = 0
        val repositoryPackages = packageRepository.queryPackages(OpType.RESTORE, "", backupDir, repositorySource = true)
        val repositoryMedia = mediaRepository.query(OpType.RESTORE, "", backupDir, repositorySource = true)

        packageRepository.queryPackages(OpType.RESTORE, "", backupDir, repositorySource = false)
            .forEach { entity ->
                val existingRepositoryEntity = repositoryPackages.firstOrNull {
                    it.packageName == entity.packageName &&
                            it.userId == entity.userId &&
                            it.preserveId == entity.preserveId &&
                            it.indexInfo.compressionType == entity.indexInfo.compressionType
                }
                val updated = entity.copy(
                    id = existingRepositoryEntity?.id ?: 0,
                    snapshotInfo = (existingRepositoryEntity?.snapshotInfo ?: entity.snapshotInfo).copy(repositorySource = true),
                    dataStates = entity.dataStates.copy(),
                )
                var importedForPackage = 0
                importPackageTypes.forEach { type ->
                    if (entity.dataState(type) != DataState.Selected) {
                        updated.dataStates = updated.dataStates.withState(type, DataState.Disabled)
                        return@forEach
                    }
                    when (val result = snapshotPackageArchive(entity, type, onMsgUpdate)) {
                        is RepositorySnapshotResult.Success -> {
                            updated.snapshotInfo.setSnapshotId(type, result.snapshotId)
                            importedForPackage++
                            appSnapshots++
                        }

                        is RepositorySnapshotResult.Failure -> {
                            updated.dataStates = updated.dataStates.withState(type, DataState.Disabled)
                            errors.add(result.message)
                        }

                        null -> {
                            updated.dataStates = updated.dataStates.withState(type, DataState.Disabled)
                        }
                    }
                }
                updated.dataStates = updated.dataStates.withState(DataType.PACKAGE_APK, DataState.Disabled)
                if (importedForPackage > 0) {
                    packageRepository.upsert(updated)
                }
            }

        mediaRepository.query(OpType.RESTORE, "", backupDir, repositorySource = false)
            .forEach { entity ->
                val existingRepositoryEntity = repositoryMedia.firstOrNull {
                    it.name == entity.name &&
                            it.preserveId == entity.preserveId &&
                            it.indexInfo.compressionType == entity.indexInfo.compressionType
                }
                when (val result = snapshotMediaArchive(entity, onMsgUpdate)) {
                    is RepositorySnapshotResult.Success -> {
                        mediaRepository.upsert(
                            entity.copy(
                                id = existingRepositoryEntity?.id ?: 0,
                                snapshotInfo = MediaSnapshotInfo(
                                    repositorySource = true,
                                    mediaSnapshotId = result.snapshotId,
                                )
                            )
                        )
                        fileSnapshots++
                    }

                    is RepositorySnapshotResult.Failure -> errors.add(result.message)
                    null -> {}
                }
            }

        return LegacyImportResult(appSnapshots = appSnapshots, fileSnapshots = fileSnapshots, errors = errors)
    }

    private suspend fun snapshotPackageArchive(
        entity: PackageEntity,
        type: DataType,
        onMsgUpdate: suspend (String) -> Unit,
    ): RepositorySnapshotResult? {
        val archivePath = packageRepository.getArchiveDst(
            dstDir = "${pathUtil.getLocalBackupAppsDir()}/${entity.archivesRelativeDir}",
            dataType = type,
            ct = entity.indexInfo.compressionType,
        )
        if (rootService.exists(archivePath).not()) return null

        val tmpDir = importTmpDir("apps/${entity.packageName}/user_${entity.userId}/${type.name}")
        rootService.deleteRecursively(tmpDir)
        rootService.mkdirs(tmpDir)
        onMsgUpdate(context.getString(R.string.legacy_importing_package_type).format(entity.packageName, type.type))
        val decompressResult = Tar.decompress(src = archivePath, dst = tmpDir, extra = entity.indexInfo.compressionType.decompressPara)
        if (decompressResult.isSuccess.not()) {
            rootService.deleteRecursively(tmpDir)
            return RepositorySnapshotResult.Failure(context.getString(R.string.legacy_extract_failed).format(archivePath))
        }

        val snapshotSource = "$tmpDir/${entity.packageName}".takeIf { rootService.exists(it) } ?: tmpDir
        val result = backupEngineRepository.snapshotDataType(entity, type, snapshotSource)
        rootService.deleteRecursively(tmpDir)
        return result
    }

    private suspend fun snapshotMediaArchive(
        entity: MediaEntity,
        onMsgUpdate: suspend (String) -> Unit,
    ): RepositorySnapshotResult? {
        val archivePath = mediaRepository.getArchiveDst(
            dstDir = "${pathUtil.getLocalBackupFilesDir()}/${entity.archivesRelativeDir}",
            ct = entity.indexInfo.compressionType,
        )
        if (rootService.exists(archivePath).not()) return null

        val tmpDir = importTmpDir("files/${entity.name}")
        rootService.deleteRecursively(tmpDir)
        rootService.mkdirs(tmpDir)
        onMsgUpdate(context.getString(R.string.legacy_importing_file).format(entity.name))
        val decompressResult = Tar.decompress(src = archivePath, dst = tmpDir, extra = entity.indexInfo.compressionType.decompressPara)
        if (decompressResult.isSuccess.not()) {
            rootService.deleteRecursively(tmpDir)
            return RepositorySnapshotResult.Failure(context.getString(R.string.legacy_extract_failed).format(archivePath))
        }

        val sourcePaths = rootService.listFilePaths(tmpDir).ifEmpty { listOf(tmpDir) }
        val result = backupEngineRepository.snapshotMedia(entity, sourcePaths)
        rootService.deleteRecursively(tmpDir)
        return result
    }

    private suspend fun countLocal10xApps(): Int {
        val backupDir = "${context.localBackupSaveDir()}/backup"
        return safeList(backupDir).sumOf { userPath ->
            safeList("$userPath/data").sumOf { packagePath ->
                safeList(packagePath).size
            }
        }
    }

    private suspend fun countLocal10xFiles(): Int {
        val backupDir = "${context.localBackupSaveDir()}/backup"
        return safeList(backupDir).sumOf { userPath ->
            safeList("$userPath/media").sumOf { mediaPath ->
                safeList(mediaPath).size
            }
        }
    }

    private suspend fun countLocal11xApps(): Int {
        val packagesDir = "${context.localBackupSaveDir()}/archives/packages"
        return safeList(packagesDir).sumOf { packagePath -> safeList(packagePath).size }
    }

    private suspend fun countLocal11xFiles(): Int {
        val mediumDir = "${context.localBackupSaveDir()}/archives/medium"
        return safeList(mediumDir).sumOf { mediaPath -> safeList(mediaPath).size }
    }

    private suspend fun countLocal12xApps(): Int {
        val appsDir = pathUtil.getLocalBackupAppsDir()
        return rootService.walkFileTree(appsDir).mapNotNull { path ->
            val pathListSize = path.pathList.size
            when {
                path.pathString.endsWith(ConfigsPackageRestoreName) && pathListSize >= 3 -> {
                    "${path.pathList[pathListSize - 3]}/${path.pathList[pathListSize - 2]}"
                }

                path.nameWithoutExtension in packageArchiveNames && CompressionType.suffixOf(path.extension) != null && pathListSize >= 3 -> {
                    "${path.pathList[pathListSize - 3]}/${path.pathList[pathListSize - 2]}"
                }

                else -> null
            }
        }.toSet().size
    }

    private suspend fun countLocal12xFiles(): Int {
        val filesDir = pathUtil.getLocalBackupFilesDir()
        return rootService.walkFileTree(filesDir).mapNotNull { path ->
            val pathListSize = path.pathList.size
            when {
                path.pathString.endsWith(ConfigsMediaRestoreName) && pathListSize >= 2 -> path.pathList[pathListSize - 2]
                path.nameWithoutExtension == DataType.MEDIA_MEDIA.type && CompressionType.suffixOf(path.extension) != null && pathListSize >= 2 -> path.pathList[pathListSize - 2]
                else -> null
            }
        }.toSet().size
    }

    private suspend fun safeList(path: String): List<String> {
        return if (rootService.exists(path)) rootService.listFilePaths(path) else emptyList()
    }

    private fun importTmpDir(relativePath: String): String {
        return "${context.filesDir()}/tmp/legacy-import/${relativePath.trim('/').replace('\\', '_')}"
    }

    private fun PackageEntity.dataState(type: DataType): DataState = when (type) {
        DataType.PACKAGE_APK -> dataStates.apkState
        DataType.PACKAGE_USER -> dataStates.userState
        DataType.PACKAGE_USER_DE -> dataStates.userDeState
        DataType.PACKAGE_DATA -> dataStates.dataState
        DataType.PACKAGE_OBB -> dataStates.obbState
        DataType.PACKAGE_MEDIA -> dataStates.mediaState
        else -> DataState.Disabled
    }

    private fun PackageDataStates.withState(type: DataType, state: DataState): PackageDataStates = when (type) {
        DataType.PACKAGE_APK -> copy(apkState = state)
        DataType.PACKAGE_USER -> copy(userState = state)
        DataType.PACKAGE_USER_DE -> copy(userDeState = state)
        DataType.PACKAGE_DATA -> copy(dataState = state)
        DataType.PACKAGE_OBB -> copy(obbState = state)
        DataType.PACKAGE_MEDIA -> copy(mediaState = state)
        else -> this
    }
}
