package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.model.DataType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.binDir
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class RepoSnapshotMeta(
    val dataTypes: MutableMap<String, String> = mutableMapOf(),
    val tags: List<String> = emptyList(),
) {
    fun snapshotIdFor(dataType: String): String? = dataTypes[dataType]
    fun putSnapshotId(dataType: String, snapshotId: String) { dataTypes[dataType] = snapshotId }
}

data class RepositoryMaintenanceProgress(
    val currentPath: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
)

class BackupEngineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) {
    companion object {
        private const val TAG = "BackupEngineRepository"
        const val LEGACY_LATEST_SNAPSHOT_ID = "latest"
    }

    private val gson = GsonUtil()
    private val catalog = BackupCatalog(gson = gson, rootService = rootService)
    private fun log(onMsg: () -> String) { LogUtil.log { TAG to onMsg() } }
    private val layout: RepositoryLayout get() = RepositoryLayout.fromRoot(context.localBackupSaveDir())
    val repositoryRoot: String get() = layout.repositoryRoot

    private fun repoPathForPackage(p: PackageEntity) = layout.appRepositoryPath(p)
    private fun repoPathForMedia(m: MediaEntity) = layout.fileRepositoryPath(m)
    private fun metaFile(repoPath: String) = File(repoPath, "databackup-snapshots.json")

    suspend fun hasPackageRepository(packageEntity: PackageEntity): Boolean = rootService.exists(File(repoPathForPackage(packageEntity), "config").path)

    suspend fun hasMediaRepository(mediaEntity: MediaEntity): Boolean = rootService.exists(File(repoPathForMedia(mediaEntity), "config").path)

    private suspend fun readMeta(repoPath: String): RepoSnapshotMeta = runCatching {
        val path = metaFile(repoPath).path
        if (rootService.exists(path)) gson.fromJson(rootService.readText(path), RepoSnapshotMeta::class.java) else RepoSnapshotMeta()
    }.getOrElse { RepoSnapshotMeta() }

    private suspend fun writeMeta(repoPath: String, meta: RepoSnapshotMeta) {
        val path = metaFile(repoPath).path
        check(rootService.writeText(gson.toJson(meta), path)) { "Failed to write repository metadata: $path" }
        rootService.setAllPermissions(repoPath)
    }

    private suspend fun relaxRepositoryPermissions(path: String) {
        if (rootService.exists(path)) {
            rootService.setAllPermissions(path)
        }
    }

    private suspend fun runRustic(vararg args: String): ShellResult {
        val result = BaseUtil.execute("rustic", *args)
        if (result.isSuccess.not()) {
            log { "rustic failed: ${result.out.joinToString("\n")}" }
        }
        return result
    }

    private fun ShellResult.createdSnapshotId(): String? {
        return out.asReversed().firstOrNull { it.isNotBlank() }?.trim()
    }

    fun isRepositoryEngineAvailable(): Boolean {
        val rusticBin = File(context.binDir(), "rustic")
        if (rusticBin.exists().not() || rusticBin.canExecute().not()) {
            log { "rustic binary not available." }
            return false
        }
        return true
    }

    suspend fun shouldUseRepositoryEngine(): Boolean = isRepositoryEngineAvailable()

    private suspend fun ensureRepo(repoPath: String) {
        val configFile = File(repoPath, "config")
        if (rootService.exists(configFile.path).not()) {
            rootService.mkdirs(repoPath)
            runRustic("init", repoPath, "x")
            relaxRepositoryPermissions(repoPath)
        }
    }

    suspend fun snapshotDataType(
        packageEntity: PackageEntity,
        dataType: DataType,
        sourcePath: String,
    ): RepositorySnapshotResult {
        val repoPath = repoPathForPackage(packageEntity)
        ensureRepo(repoPath)
        val tag = "type:${dataType.name},app:${packageEntity.packageName},user:${packageEntity.userId}"
        val result = runRustic("backup", repoPath, "x", tag, sourcePath)
        if (!result.isSuccess) return RepositorySnapshotResult.Failure("Failed to snapshot $dataType for ${packageEntity.packageName}")
        relaxRepositoryPermissions(repoPath)
        val snapshotId = result.createdSnapshotId()
            ?: return RepositorySnapshotResult.Failure("Snapshot id missing for $dataType of ${packageEntity.packageName}")

        runCatching {
            val meta = readMeta(repoPath)
            meta.putSnapshotId(dataType.name, snapshotId)
            writeMeta(repoPath, meta)
            catalog.recordPackageSnapshot(layout, packageEntity, repoPath, dataType, snapshotId)
        }.onFailure {
            val message = "Failed to write repository metadata for $dataType of ${packageEntity.packageName}: ${it.localizedMessage ?: it}"
            log { message }
            return RepositorySnapshotResult.Failure(message)
        }
        log { "Snapshotted $dataType for ${packageEntity.packageName}: $snapshotId" }
        return RepositorySnapshotResult.Success(snapshotId, repoPath, emptyList())
    }

    suspend fun restoreDataType(
        packageEntity: PackageEntity,
        dataType: DataType,
        targetDir: String,
        snapshotId: String,
    ): RepositorySnapshotResult {
        if (snapshotId.isBlank()) return RepositorySnapshotResult.Failure("Snapshot id missing for $dataType of ${packageEntity.packageName}")
        val repoPath = repoPathForPackage(packageEntity)
        ensureRepo(repoPath)
        val result = runRustic("restore", repoPath, "x", snapshotId, targetDir)
        if (!result.isSuccess) return RepositorySnapshotResult.Failure("Failed to restore $dataType for ${packageEntity.packageName}")
        relaxRepositoryPermissions(repoPath)
        return RepositorySnapshotResult.Success(snapshotId, repoPath, emptyList())
    }

    suspend fun snapshotMedia(mediaEntity: MediaEntity, sourcePaths: List<String>): RepositorySnapshotResult {
        val repoPath = repoPathForMedia(mediaEntity)
        ensureRepo(repoPath)
        val tag = "file:${mediaEntity.name.replace("/", "_")}"
        val allArgs = mutableListOf("backup", repoPath, "x", tag)
        allArgs.addAll(sourcePaths)
        val result = runRustic(*allArgs.toTypedArray())
        if (!result.isSuccess) return RepositorySnapshotResult.Failure("Failed to snapshot ${mediaEntity.name}")
        relaxRepositoryPermissions(repoPath)
        val snapshotId = result.createdSnapshotId()
            ?: return RepositorySnapshotResult.Failure("Snapshot id missing for ${mediaEntity.name}")
        runCatching {
            val meta = readMeta(repoPath)
            meta.putSnapshotId(DataType.MEDIA_MEDIA.name, snapshotId)
            writeMeta(repoPath, meta)
            catalog.recordMediaSnapshot(layout, mediaEntity, repoPath, snapshotId)
        }.onFailure {
            val message = "Failed to write repository metadata for ${mediaEntity.name}: ${it.localizedMessage ?: it}"
            log { message }
            return RepositorySnapshotResult.Failure(message)
        }
        log { "Snapshotted ${mediaEntity.name} into repository: $repoPath ($snapshotId)" }
        return RepositorySnapshotResult.Success(snapshotId, repoPath, emptyList())
    }

    suspend fun restoreMedia(mediaEntity: MediaEntity, targetDir: String, snapshotId: String): RepositorySnapshotResult {
        if (snapshotId.isBlank()) return RepositorySnapshotResult.Failure("Snapshot id missing for ${mediaEntity.name}")
        val repoPath = repoPathForMedia(mediaEntity)
        ensureRepo(repoPath)
        val result = runRustic("restore", repoPath, "x", snapshotId, targetDir)
        if (!result.isSuccess) return RepositorySnapshotResult.Failure("Failed to restore ${mediaEntity.name}")
        relaxRepositoryPermissions(repoPath)
        return RepositorySnapshotResult.Success(snapshotId, repoPath, emptyList())
    }

    suspend fun checkRepository(repositoryPath: String): RepositorySnapshotResult {
        log { "Checking repository: $repositoryPath" }
        val result = runRustic("check", repositoryPath, "x")
        if (result.isSuccess) {
            log { "Repository check succeeded: $repositoryPath" }
            return RepositorySnapshotResult.Success("", repositoryPath, emptyList())
        }
        log { "Repository check failed: $repositoryPath" }
        return RepositorySnapshotResult.Failure("Repository check failed: $repositoryPath")
    }

    suspend fun checkAllRepositories(
        onProgress: suspend (RepositoryMaintenanceProgress) -> Unit = {},
    ): List<RepositorySnapshotResult> {
        val rootDir = File(repositoryRoot)
        log { "Checking all repositories under: ${rootDir.path}" }
        if (rootService.exists(rootDir.path).not()) {
            log { "Repository root does not exist: ${rootDir.path}" }
            onProgress(RepositoryMaintenanceProgress(totalItems = 0))
            return emptyList()
        }
        relaxRepositoryPermissions(repositoryRoot)
        val repoDirs = rootService.walkFileTree(repositoryRoot)
            .mapNotNull { path ->
                if (path.pathString.endsWith("/config") || path.pathString.endsWith("\\config")) {
                    PathUtil.getParentPath(path.pathString)
                } else {
                    null
                }
            }
            .distinct()
        log { "Repository check target count: ${repoDirs.size}" }
        val results = mutableListOf<RepositorySnapshotResult>()
        onProgress(RepositoryMaintenanceProgress(totalItems = repoDirs.size))
        repoDirs.forEachIndexed { index, repoDir ->
            log { "Checking repository ${index + 1}/${repoDirs.size}: $repoDir" }
            onProgress(
                RepositoryMaintenanceProgress(
                    currentPath = repoDir,
                    processedItems = index,
                    totalItems = repoDirs.size,
                )
            )
            results.add(checkRepository(repoDir))
            onProgress(
                RepositoryMaintenanceProgress(
                    currentPath = repoDir,
                    processedItems = index + 1,
                    totalItems = repoDirs.size,
                )
            )
        }
        return results
    }

    fun getRepositorySize(): Long {
        val rootDir = File(repositoryRoot)
        if (rootDir.exists().not()) return 0L
        return rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearAllRepositories(
        onProgress: suspend (RepositoryMaintenanceProgress) -> Unit = {},
    ): Boolean {
        val rootDir = File(repositoryRoot)
        log { "Clearing repository root: ${rootDir.path}" }
        if (rootService.exists(rootDir.path).not()) {
            log { "Repository root does not exist, nothing to clear: ${rootDir.path}" }
            onProgress(RepositoryMaintenanceProgress(totalItems = 0))
            return true
        }

        relaxRepositoryPermissions(rootDir.path)
        val targets = (rootService.walkFileTree(rootDir.path).map { it.pathString } + rootDir.path).distinct()
        log { "Repository clear target count: ${targets.size}" }
        onProgress(RepositoryMaintenanceProgress(totalItems = targets.size))

        var success = true
        targets.forEachIndexed { index, target ->
            log { "Clearing repository target ${index + 1}/${targets.size}: $target" }
            onProgress(
                RepositoryMaintenanceProgress(
                    currentPath = target,
                    processedItems = index,
                    totalItems = targets.size,
                )
            )
            val deleted = rootService.deleteRecursively(target) || rootService.exists(target).not()
            if (deleted.not()) {
                log { "Repository path still exists after root-service delete attempt: $target" }
            }
            success = success && deleted
            onProgress(
                RepositoryMaintenanceProgress(
                    currentPath = target,
                    processedItems = index + 1,
                    totalItems = targets.size,
                )
            )
        }

        val remainingRoot = rootService.exists(rootDir.path)
        log { "Clear repositories finished: success=$success, remainingRoot=$remainingRoot" }
        return success && remainingRoot.not()
    }
}

sealed interface RepositorySnapshotResult {
    val isSuccess: Boolean
    data class Success(val snapshotId: String, val repositoryPath: String, val changedFiles: List<String>) : RepositorySnapshotResult {
        override val isSuccess: Boolean = true
    }
    data class Failure(val message: String) : RepositorySnapshotResult {
        override val isSuccess: Boolean = false
    }
}
