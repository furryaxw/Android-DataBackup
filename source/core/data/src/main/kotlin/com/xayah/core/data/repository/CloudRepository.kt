package com.xayah.core.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.google.gson.reflect.TypeToken
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.datastore.readCloudUploadRetries
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.datastore.readSyncConcurrency
import com.xayah.core.model.SyncDirection
import com.xayah.core.model.backup.PendingUploadQueue
import com.xayah.core.model.database.SyncFileTaskEntity
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class RepositorySyncStats(
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
)

data class CachedRepositorySyncStats(
    val stats: RepositorySyncStats = RepositorySyncStats(),
    val updatedAt: Long = 0,
)

data class RepositorySyncProgress(
    val relativePath: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val currentReadBytes: Long = 0,
    val currentTotalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
)

data class RepositorySyncResult(
    val stats: RepositorySyncStats = RepositorySyncStats(),
    val transferStats: RepositorySyncStats = stats,
    val errors: List<String> = emptyList(),
) {
    val isSuccess: Boolean
        get() = errors.isEmpty()
}

private data class LocalRepositoryFile(
    val relativePath: String,
    val path: String,
    val bytes: Long,
    val sha256: String = "",
)

private data class RepositoryMetaFile(
    val relativePath: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
)

private data class RepositoryMeta(
    val version: Int = 1,
    val repositoryRoot: String = "",
    val generatedAt: Long = 0,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val files: List<RepositoryMetaFile> = emptyList(),
)

private data class RepositoryFileSnapshot(
    val files: List<RepositoryMetaFile> = emptyList(),
    val updatedAt: Long = 0,
) {
    val stats: RepositorySyncStats
        get() = RepositorySyncStats(
            fileCount = files.size,
            totalBytes = files.sumOf { it.bytes },
        )
}

class RepositorySyncCancellationToken {
    @Volatile
    private var cancelled: Boolean = false
    private val activeClients = ConcurrentHashMap.newKeySet<CloudClient>()

    val isCancelled: Boolean
        get() = cancelled

    fun cancel() {
        cancelled = true
        activeClients.forEach { client ->
            runCatching { client.disconnect() }
        }
    }

    fun throwIfCancelled() {
        if (cancelled) throw CancellationException("Repository sync stopped.")
    }

    internal fun register(client: CloudClient) {
        throwIfCancelled()
        activeClients.add(client)
        if (cancelled) {
            runCatching { client.disconnect() }
            throwIfCancelled()
        }
    }

    internal fun unregister(client: CloudClient) {
        activeClients.remove(client)
    }
}

class CloudRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val cloudDao: CloudDao,
    private val syncRepository: SyncRepository,
) {
    private companion object {
        const val REPOSITORY_META_VERSION = 2
    }

    private val gson = com.xayah.core.util.GsonUtil()

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "CloudRepository" to msg() }
        msg()
    }

    fun getString(@StringRes resId: Int) = context.getString(resId)
    suspend fun upsert(item: CloudEntity) = cloudDao.upsert(prepareCloudForUpsert(item))
    suspend fun upsert(items: List<CloudEntity>) = cloudDao.upsert(items.map { prepareCloudForUpsert(it) })
    suspend fun queryByName(name: String) = cloudDao.queryByName(name)
    suspend fun query() = cloudDao.query()

    val clouds = cloudDao.queryFlow().distinctUntilChanged()

    suspend fun delete(entity: CloudEntity) = cloudDao.delete(entity)

    suspend fun cachedRepositoryStats(cloud: String, remoteRoot: String): CachedRepositorySyncStats? {
        val entity = cloudDao.queryByName(cloud) ?: return null
        if (entity.repositoryCacheUpdatedAt == 0L || entity.repositoryCachePath != remoteRoot) return null
        return CachedRepositorySyncStats(
            stats = RepositorySyncStats(
                fileCount = entity.repositoryCacheFiles,
                totalBytes = entity.repositoryCacheBytes,
            ),
            updatedAt = entity.repositoryCacheUpdatedAt,
        )
    }

    suspend fun updateCachedRepositoryStats(cloud: String, remoteRoot: String, stats: RepositorySyncStats) {
        cacheRepositoryStats(cloud = cloud, remoteRoot = remoteRoot, stats = stats)
    }

    private suspend fun prepareCloudForUpsert(item: CloudEntity): CloudEntity {
        val existing = cloudDao.queryByName(item.name) ?: return item
        return if (existing.hasSameRepositoryEndpoint(item)) {
            if (item.repositoryCacheUpdatedAt == 0L) {
                item.withRepositoryCacheFrom(existing)
            } else {
                item
            }
        } else {
            item.clearRepositoryCache()
        }
    }

    private suspend fun cacheRepositoryStats(
        cloud: String,
        remoteRoot: String,
        stats: RepositorySyncStats,
        updatedAt: Long = DateUtil.getTimestamp(),
    ) {
        val entity = cloudDao.queryByName(cloud) ?: return
        cloudDao.upsert(
            entity.copy(
                repositoryCachePath = remoteRoot,
                repositoryCacheFiles = stats.fileCount,
                repositoryCacheBytes = stats.totalBytes,
                repositoryCacheUpdatedAt = updatedAt,
            )
        )
    }

    private fun CloudEntity.hasSameRepositoryEndpoint(other: CloudEntity): Boolean {
        return type == other.type &&
                host == other.host &&
                user == other.user &&
                pass == other.pass &&
                remote == other.remote &&
                extra == other.extra
    }

    private fun CloudEntity.withRepositoryCacheFrom(other: CloudEntity): CloudEntity {
        return copy(
            repositoryCachePath = other.repositoryCachePath,
            repositoryCacheFiles = other.repositoryCacheFiles,
            repositoryCacheBytes = other.repositoryCacheBytes,
            repositoryCacheUpdatedAt = other.repositoryCacheUpdatedAt,
        )
    }

    private fun CloudEntity.clearRepositoryCache(): CloudEntity {
        return copy(
            repositoryCachePath = "",
            repositoryCacheFiles = 0,
            repositoryCacheBytes = 0,
            repositoryCacheUpdatedAt = 0,
        )
    }

    private suspend fun uploadWithRetry(
        client: CloudClient,
        src: String,
        dstDir: String,
        onUploading: (read: Long, total: Long) -> Unit,
        skipIfSameSize: Boolean = false,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ) {
        var lastFailure: Throwable? = null
        val maxAttempts = context.readCloudUploadRetries().first().coerceAtLeast(1)
        repeat(maxAttempts) { attempt ->
            cancellationToken?.throwIfCancelled()
            runCatching {
                uploadFileAtomically(
                    client = client,
                    src = src,
                    dstDir = dstDir,
                    onUploading = onUploading,
                    skipIfSameSize = skipIfSameSize,
                    cancellationToken = cancellationToken,
                )
            }.onSuccess {
                return
            }.onFailure {
                if (it is CancellationException) throw it
                lastFailure = it
                if (attempt < maxAttempts - 1) {
                    delay((attempt + 1) * 1000L)
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Upload failed.")
    }

    private suspend fun uploadFileAtomically(
        client: CloudClient,
        src: String,
        dstDir: String,
        onUploading: (read: Long, total: Long) -> Unit,
        skipIfSameSize: Boolean = false,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ) {
        cancellationToken?.throwIfCancelled()
        if (rootService.exists(src).not()) {
            throw IllegalStateException("Source file does not exist: $src")
        }
        val srcFile = File(src)
        val srcSize = runCatching { if (srcFile.exists()) srcFile.length() else rootService.calculateSize(src) }
            .getOrDefault(rootService.calculateSize(src))
        val srcName = srcFile.name
        val remoteFinal = "$dstDir/$srcName"
        val partialName = "${src.hashCode() and 0x7FFFFFFF}-${srcName}.partial"
        val remotePartial = "$dstDir/$partialName"

        if (skipIfSameSize && client.exists(remoteFinal) && client.size(remoteFinal) == srcSize) {
            onUploading(srcSize, srcSize)
            return
        }
        cancellationToken?.throwIfCancelled()

        val existingSize = runCatching { client.size(remotePartial) }.getOrDefault(0L)
        if (existingSize > 0 && existingSize >= srcSize) {
            runCatching { client.renameTo(remotePartial, remoteFinal) }
            onUploading(srcSize, srcSize)
            return
        }

        val partialFile = File(context.cacheDir, partialName)
        try {
            partialFile.delete()
            check(rootService.copyTo(src, partialFile.path, overwrite = true)) {
                "Failed to stage source file for upload: $src"
            }
            rootService.setAllPermissions(partialFile.path)
            cancellationToken?.throwIfCancelled()
            runCatching { client.mkdirRecursively(dstDir) }.getOrElse {
                throw IllegalStateException("Failed to create remote directory: $dstDir", it)
            }
            val resumeOffset = if (existingSize > 0 && existingSize < srcSize) {
                log { "Resuming upload of $srcName from offset $existingSize" }
                existingSize
            } else {
                runCatching { if (client.exists(remotePartial)) client.deleteFile(remotePartial) }
                0L
            }
            runCatching {
                cancellationToken?.throwIfCancelled()
                client.uploadResume(src = partialFile.path, dst = dstDir, resumeOffset = resumeOffset, onUploading = onUploading)
            }.onFailure { e ->
                if (resumeOffset > 0 && e is UnsupportedOperationException) {
                    runCatching { if (client.exists(remotePartial)) client.deleteFile(remotePartial) }
                    cancellationToken?.throwIfCancelled()
                    client.uploadResume(src = partialFile.path, dst = dstDir, resumeOffset = 0L, onUploading = onUploading)
                } else {
                    throw e
                }
            }

            cancellationToken?.throwIfCancelled()
            val uploadedSize = client.size(remotePartial)
            if (uploadedSize != srcSize) {
                throw IllegalStateException("Uploaded partial size mismatch: expected $srcSize, got $uploadedSize for $remotePartial")
            }
            runCatching { if (client.exists(remoteFinal)) client.deleteFile(remoteFinal) }
            client.renameTo(remotePartial, remoteFinal)
        } finally {
            partialFile.delete()
        }
    }

    suspend fun upload(client: CloudClient, src: String, dstDir: String, onUploading: (read: Long, total: Long) -> Unit = { _, _ -> }): ShellResult = run {
        upload(client = client, src = src, dstDir = dstDir, deleteAfterUploaded = true, onUploading = onUploading)
    }

    suspend fun upload(
        client: CloudClient,
        src: String,
        dstDir: String,
        deleteAfterUploaded: Boolean,
        onUploading: (read: Long, total: Long) -> Unit = { _, _ -> },
        skipIfSameSize: Boolean = false,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): ShellResult = run {
        log { "Uploading..." }

        var isSuccess = true
        val out = mutableListOf<String>()
        if (rootService.exists(src).not()) {
            out.add(log { "Source file does not exist: $src" })
            return ShellResult(code = -1, input = listOf(), out = out)
        }
        PathUtil.setFilesDirSELinux(context)

        runCatching {
            uploadWithRetry(
                client = client,
                src = src,
                dstDir = dstDir,
                onUploading = onUploading,
                skipIfSameSize = skipIfSameSize,
                cancellationToken = cancellationToken,
            )
            out.add("Upload succeed.")
        }.onFailure {
            if (it is CancellationException) throw it
            cancellationToken?.throwIfCancelled()
            isSuccess = false
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            it.printStackTrace(printWriter)
            if (it.localizedMessage != null)
                out.add(log { stringWriter.toString() })
        }

        if (isSuccess && deleteAfterUploaded) {
            rootService.deleteRecursively(src).also { result ->
                isSuccess = isSuccess and result
                if (result.not()) out.add(log { "Failed to delete $src." })
            }
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun enqueueUpload(cloud: String, src: String, dstDir: String) {
        val file = File(src)
        syncRepository.createTask(
            direction = SyncDirection.PUSH,
            cloud = cloud,
            localRoot = PathUtil.getParentPath(src),
            remoteRoot = dstDir,
            files = listOf(
                SyncFilePlan(
                    relativePath = file.name,
                    localPath = src,
                    remotePath = "$dstDir/${file.name}",
                    remoteDir = dstDir,
                    bytes = file.length(),
                )
            ),
        )
    }

    suspend fun localRepositoryStats(repoRoot: String): RepositorySyncStats {
        val localMeta = readLocalRepositoryMeta(repoRoot)
        val effectiveSnapshot = localMeta ?: run {
            log { "RepositoryMeta local cache miss; rebuilding local repository meta: $repoRoot" }
            runCatching { rebuildLocalRepositoryMeta(repoRoot = repoRoot) }
                .onSuccess {
                    log {
                        "RepositoryMeta local cache rebuilt: root=$repoRoot files=${it.stats.fileCount} bytes=${it.stats.totalBytes} updatedAt=${it.updatedAt}"
                    }
                }
                .onFailure {
                    log { "RepositoryMeta local rebuild failed; scanning local repository: $repoRoot error=${it.describe()}" }
                }
                .getOrNull()
        }
        val stats = effectiveSnapshot?.stats ?: run {
            val files = localRepositoryFiles(repoRoot = repoRoot, calculateSha256 = false)
            RepositorySyncStats(fileCount = files.size, totalBytes = files.sumOf { it.bytes })
        }
        if (localMeta != null) {
            log { "RepositoryMeta local cache hit: root=$repoRoot files=${stats.fileCount} bytes=${stats.totalBytes} updatedAt=${localMeta.updatedAt}" }
        }
        log { "Local repository stats for $repoRoot: ${stats.fileCount} files, ${stats.totalBytes} bytes" }
        return stats
    }

    suspend fun refreshLocalRepositoryMeta(repoRoot: String): RepositorySyncStats {
        log { "RepositoryMeta local refresh requested: root=$repoRoot" }
        val snapshot = rebuildLocalRepositoryMeta(repoRoot = repoRoot)
        log { "Refreshed local repository meta for $repoRoot: ${snapshot.stats.fileCount} files, ${snapshot.stats.totalBytes} bytes" }
        return snapshot.stats
    }

    suspend fun remoteRepositoryStats(cloud: String, remoteRoot: String): RepositorySyncStats {
        var snapshot: RepositoryFileSnapshot? = null
        withClient(cloud) { client, _ ->
            snapshot = if (client.exists(remoteRoot)) {
                readRemoteRepositoryMeta(client = client, remoteRoot = remoteRoot)
                    ?: rebuildRemoteRepositoryMeta(client = client, remoteRoot = remoteRoot)
            } else {
                RepositoryFileSnapshot()
            }
        }
        val stats = snapshot?.stats ?: RepositorySyncStats()
        cacheRepositoryStats(
            cloud = cloud,
            remoteRoot = remoteRoot,
            stats = stats,
            updatedAt = snapshot?.updatedAt?.takeIf { it > 0 } ?: DateUtil.getTimestamp(),
        )
        return stats
    }

    suspend fun enqueueRepositoryFiles(cloud: String, remoteBase: String, repoRoot: String): RepositorySyncStats {
        val files = localRepositoryFiles(repoRoot = repoRoot, calculateSha256 = false)
        syncRepository.createTask(
            direction = SyncDirection.PUSH,
            cloud = cloud,
            localRoot = repoRoot,
            remoteRoot = remoteBase,
            files = files.map { file ->
                val remoteParent = PathUtil.getParentPath(file.relativePath)
                val remoteDir = if (remoteParent.isEmpty()) remoteBase else "$remoteBase/$remoteParent"
                SyncFilePlan(
                    relativePath = file.relativePath,
                    localPath = file.path,
                    remotePath = "$remoteDir/${PathUtil.getFileName(file.path)}",
                    remoteDir = remoteDir,
                    bytes = file.bytes,
                )
            },
        )
        return RepositorySyncStats(fileCount = files.size, totalBytes = files.sumOf { it.bytes })
    }

    suspend fun forcePushRepository(
        cloud: String,
        localRepositoryRoot: String,
        remoteRepositoryRoot: String,
        onProgress: (RepositorySyncProgress) -> Unit = {},
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): RepositorySyncResult {
        log { "Starting force push: local=$localRepositoryRoot remote=$remoteRepositoryRoot cloud=$cloud" }
        cancellationToken?.throwIfCancelled()
        if (rootService.exists(localRepositoryRoot).not()) {
            log { "Force push aborted; local repository does not exist: $localRepositoryRoot" }
            return RepositorySyncResult(errors = listOf("Local repository does not exist: $localRepositoryRoot"))
        }

        val files = localRepositoryFiles(repoRoot = localRepositoryRoot, calculateSha256 = true, cancellationToken = cancellationToken)
        writeLocalRepositoryMeta(localRepositoryRoot, files.toRepositoryMetaFiles())
        val totalBytes = files.sumOf { it.bytes }
        log { "Force push file count: ${files.size}" }
        val relativePaths = files.map { it.relativePath }.toSet()
        var previousRemoteFiles = emptyList<RepositoryMetaFile>()
        val errors = mutableListOf<String>()
        val errorsMutex = Mutex()
        val progressMutex = Mutex()
        runCatching {
            withClient(cloud, cancellationToken = cancellationToken) { client, _ ->
                cancellationToken?.throwIfCancelled()
                val remoteExists = client.exists(remoteRepositoryRoot)
                previousRemoteFiles = if (remoteExists) {
                    val remoteSnapshot = readRemoteRepositoryMeta(client = client, remoteRoot = remoteRepositoryRoot)
                    cancellationToken?.throwIfCancelled()
                    remoteSnapshot?.files
                        ?: scanRemoteRepositoryFiles(client = client, remoteRoot = remoteRepositoryRoot, cancellationToken = cancellationToken)
                } else {
                    emptyList()
                }
                client.mkdirRecursively(remoteRepositoryRoot)
                if (client.exists(remoteRepositoryRoot).not()) {
                    throw IllegalStateException("Failed to create remote repository directory: $remoteRepositoryRoot")
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            errors.add(it.describe())
        }

        val previousRemoteFilesByPath = previousRemoteFiles.associateBy { it.relativePath }
        cancellationToken?.throwIfCancelled()
        val transferFiles = files.filterNot { file ->
            previousRemoteFilesByPath[file.relativePath]?.hasSameContentAs(file) == true
        }
        val transferBytes = transferFiles.sumOf { it.bytes }
        onProgress(RepositorySyncProgress(totalItems = transferFiles.size, totalBytes = transferBytes))
        log { "Force push transfer file count: ${transferFiles.size}" }
        if (errors.isNotEmpty()) {
            return RepositorySyncResult(
                stats = RepositorySyncStats(fileCount = files.size, totalBytes = totalBytes),
                transferStats = RepositorySyncStats(fileCount = transferFiles.size, totalBytes = transferBytes),
                errors = errors,
            )
        }

        val processedFiles = AtomicInteger(0)
        val processedBytes = AtomicLong(0L)
        val activeBytes = ConcurrentHashMap<String, Long>()
        fun transferredBytes(): Long = (processedBytes.get() + activeBytes.values.sum()).coerceAtMost(transferBytes)
        fun emitProgress(relativePath: String, read: Long, bytes: Long) {
            onProgress(
                RepositorySyncProgress(
                    relativePath = relativePath,
                    processedItems = processedFiles.get(),
                    totalItems = transferFiles.size,
                    currentReadBytes = read,
                    currentTotalBytes = bytes,
                    transferredBytes = transferredBytes(),
                    totalBytes = transferBytes,
                )
            )
        }
        val syncTaskId = syncRepository.createTask(
            direction = SyncDirection.PUSH,
            cloud = cloud,
            localRoot = localRepositoryRoot,
            remoteRoot = remoteRepositoryRoot,
            files = transferFiles.map { file ->
                val remoteDir = remoteParentDir(remoteRoot = remoteRepositoryRoot, relativePath = file.relativePath)
                SyncFilePlan(
                    relativePath = file.relativePath,
                    localPath = file.path,
                    remotePath = "$remoteDir/${PathUtil.getFileName(file.path)}",
                    remoteDir = remoteDir,
                    bytes = file.bytes,
                )
            },
        )
        syncRepository.markTaskStarted(syncTaskId, SyncDirection.PUSH)

        runCatching {
            processTaskConcurrently(
                syncTaskId = syncTaskId,
                direction = SyncDirection.PUSH,
                cloud = cloud,
                totalFiles = transferFiles.size,
                cancellationToken = cancellationToken,
            ) { client, fileTask, _ ->
                cancellationToken?.throwIfCancelled()
                val relativePath = fileTask.relativePath
                val remoteDir = fileTask.remoteDir
                log { "Uploading repository file: $relativePath" }
                relaxLocalFileForAppRead(root = localRepositoryRoot.trimEnd('/'), path = fileTask.localPath)
                if (rootService.exists(fileTask.localPath).not()) {
                    val error = "Local file does not exist: ${fileTask.localPath}"
                    errorsMutex.withLock { errors.add(error) }
                    syncRepository.markFileError(syncTaskId, relativePath, error)
                    return@processTaskConcurrently
                }

                upload(
                    client = client,
                    src = fileTask.localPath,
                    dstDir = remoteDir,
                    deleteAfterUploaded = false,
                    skipIfSameSize = false,
                    cancellationToken = cancellationToken,
                    onUploading = { read, bytes ->
                        if (cancellationToken?.isCancelled != true) {
                            activeBytes[relativePath] = read
                            emitProgress(relativePath, read, bytes)
                        }
                    },
                ).also { result ->
                    cancellationToken?.throwIfCancelled()
                    if (result.isSuccess.not()) {
                        activeBytes.remove(relativePath)
                        val error = result.outString.ifEmpty { "Failed to upload $relativePath" }
                        errorsMutex.withLock { errors.add(error) }
                        syncRepository.markFileError(syncTaskId, relativePath, error)
                    } else {
                        val bytes = fileTask.bytes
                        syncRepository.markFileDone(syncTaskId, relativePath, bytes)
                        log { "Uploaded repository file: $relativePath" }
                        progressMutex.withLock {
                            activeBytes.remove(relativePath)
                            val doneFiles = processedFiles.incrementAndGet()
                            val doneBytes = processedBytes.addAndGet(bytes)
                            syncRepository.updateTaskProgress(syncTaskId, doneFiles, doneBytes)
                            onProgress(
                                RepositorySyncProgress(
                                    relativePath = relativePath,
                                    processedItems = doneFiles,
                                    totalItems = transferFiles.size,
                                    currentReadBytes = bytes,
                                    currentTotalBytes = bytes,
                                    transferredBytes = transferredBytes(),
                                    totalBytes = transferBytes,
                                )
                            )
                        }
                    }
                }
            }

            withClient(cloud, cancellationToken = cancellationToken) { client, _ ->
                cancellationToken?.throwIfCancelled()
                val staleRelativePaths = previousRemoteFiles
                    .map { it.relativePath }
                    .filter { it.isNotEmpty() && it !in relativePaths }
                staleRelativePaths.forEach { relativePath ->
                    try {
                        client.deleteFile(remotePath(remoteRoot = remoteRepositoryRoot, relativePath = relativePath))
                    } catch (e: Throwable) {
                        errorsMutex.withLock { errors.add("Failed to delete remote stale file $relativePath: ${e.describe()}") }
                    }
                }
                clearKnownEmptyRemoteParents(
                    client = client,
                    remoteRoot = remoteRepositoryRoot,
                    relativePaths = staleRelativePaths,
                )
                if (errors.isEmpty()) {
                    runCatching {
                        writeRemoteRepositoryMeta(
                            client = client,
                            remoteRoot = remoteRepositoryRoot,
                            files = files.toRepositoryMetaFiles(),
                            cancellationToken = cancellationToken,
                        )
                    }.onFailure {
                        errors.add("Failed to write remote repository meta: ${it.describe()}")
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            errors.add(it.describe())
        }

        val result = RepositorySyncResult(
            stats = RepositorySyncStats(fileCount = files.size, totalBytes = totalBytes),
            transferStats = RepositorySyncStats(fileCount = transferFiles.size, totalBytes = transferBytes),
            errors = errors,
        )
        log { "Force push finished: success=${result.isSuccess}, files=${result.stats.fileCount}, errors=${result.errors.size}" }
        logRepositoryErrors("Force push", result.errors)
        syncRepository.markTaskFinished(syncTaskId, result)
        return result
    }

    suspend fun forcePullRepository(
        cloud: String,
        remoteRepositoryRoot: String,
        localRepositoryRoot: String,
        onProgress: (RepositorySyncProgress) -> Unit = {},
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): RepositorySyncResult {
        log { "Starting force pull: remote=$remoteRepositoryRoot local=$localRepositoryRoot cloud=$cloud" }
        cancellationToken?.throwIfCancelled()
        val rootDir = File(localRepositoryRoot)
        if (rootDir.name != RepositoryLayout.REPOSITORY_DIR) {
            log { "Force pull aborted; local target is not a repository directory: $localRepositoryRoot" }
            return RepositorySyncResult(errors = listOf("Refusing to sync into non-repository directory: $localRepositoryRoot"))
        }
        if (rootService.exists(localRepositoryRoot)) {
            rootService.setAllPermissions(localRepositoryRoot)
        }

        val errors = mutableListOf<String>()
        val errorsMutex = Mutex()
        val progressMutex = Mutex()
        val processedFiles = AtomicInteger(0)
        val processedBytes = AtomicLong(0L)
        val activeBytes = ConcurrentHashMap<String, Long>()
        var remoteRelativePaths = emptySet<String>()
        var stats = RepositorySyncStats()
        var remoteSnapshot = RepositoryFileSnapshot()
        var syncTaskId: Long? = null
        var transferStats = RepositorySyncStats()
        fun transferredBytes(): Long = (processedBytes.get() + activeBytes.values.sum()).coerceAtMost(transferStats.totalBytes)
        fun emitProgress(relativePath: String, read: Long, bytes: Long) {
            onProgress(
                RepositorySyncProgress(
                    relativePath = relativePath,
                    processedItems = processedFiles.get(),
                    totalItems = transferStats.fileCount,
                    currentReadBytes = read,
                    currentTotalBytes = bytes,
                    transferredBytes = transferredBytes(),
                    totalBytes = transferStats.totalBytes,
                )
            )
        }

        runCatching {
            var plans = emptyList<SyncFilePlan>()
            val localSnapshot = rebuildLocalRepositoryMeta(localRepositoryRoot, cancellationToken = cancellationToken)
            val localFilesByPath = localSnapshot.files.associateBy { it.relativePath }
            withClient(cloud, cancellationToken = cancellationToken) { client, _ ->
                cancellationToken?.throwIfCancelled()
                if (client.exists(remoteRepositoryRoot).not()) {
                    log { "Force pull aborted; remote repository does not exist: $remoteRepositoryRoot" }
                    errors.add("Remote repository does not exist: $remoteRepositoryRoot")
                    return@withClient
                }

                val parsedRemoteSnapshot = readRemoteRepositoryMeta(client = client, remoteRoot = remoteRepositoryRoot)
                cancellationToken?.throwIfCancelled()
                remoteSnapshot = parsedRemoteSnapshot
                    ?: rebuildRemoteRepositoryMeta(client = client, remoteRoot = remoteRepositoryRoot, cancellationToken = cancellationToken)
                cancellationToken?.throwIfCancelled()
                val transferFiles = remoteSnapshot.files.filterNot { remoteFile ->
                    localFilesByPath[remoteFile.relativePath].hasSameContentAs(remoteFile)
                }
                transferStats = RepositorySyncStats(
                    fileCount = transferFiles.size,
                    totalBytes = transferFiles.sumOf { it.bytes },
                )
                plans = transferFiles.map { remoteFile ->
                    val remotePath = remotePath(remoteRoot = remoteRepositoryRoot, relativePath = remoteFile.relativePath)
                    SyncFilePlan(
                        relativePath = remoteFile.relativePath,
                        localPath = File(rootDir, remoteFile.relativePath).absolutePath,
                        remotePath = remotePath,
                        remoteDir = PathUtil.getParentPath(remotePath),
                        bytes = remoteFile.bytes,
                    )
                }
                remoteRelativePaths = remoteSnapshot.files.map { it.relativePath }.toSet()
                stats = remoteSnapshot.stats
                onProgress(RepositorySyncProgress(totalItems = transferStats.fileCount, totalBytes = transferStats.totalBytes))
                log { "Force pull transfer file count: ${plans.size}" }
                syncTaskId = syncRepository.createTask(
                    direction = SyncDirection.PULL,
                    cloud = cloud,
                    localRoot = localRepositoryRoot,
                    remoteRoot = remoteRepositoryRoot,
                    files = plans,
                )
                syncRepository.markTaskStarted(syncTaskId!!, SyncDirection.PULL)
            }

            val taskId = syncTaskId ?: return@runCatching
            rootService.mkdirs(localRepositoryRoot)
            processTaskConcurrently(
                syncTaskId = taskId,
                direction = SyncDirection.PULL,
                cloud = cloud,
                totalFiles = plans.size,
                cancellationToken = cancellationToken,
            ) { client, fileTask, _ ->
                cancellationToken?.throwIfCancelled()
                val relativePath = fileTask.relativePath
                val localParent = localParentDir(rootDir = rootDir, relativePath = relativePath)
                localParent.mkdirs()
                val remoteBytes = fileTask.bytes
                log { "Downloading repository file: $relativePath" }
                try {
                    cancellationToken?.throwIfCancelled()
                    client.download(src = fileTask.remotePath, dst = localParent.absolutePath) { read, bytes ->
                        if (cancellationToken?.isCancelled != true) {
                            activeBytes[relativePath] = read
                            emitProgress(relativePath, read, bytes)
                        }
                    }
                    cancellationToken?.throwIfCancelled()
                    syncRepository.markFileDone(taskId, relativePath, remoteBytes)
                    log { "Downloaded repository file: $relativePath" }
                    progressMutex.withLock {
                        activeBytes.remove(relativePath)
                        val doneFiles = processedFiles.incrementAndGet()
                        val doneBytes = processedBytes.addAndGet(remoteBytes)
                        syncRepository.updateTaskProgress(taskId, doneFiles, doneBytes)
                        onProgress(
                            RepositorySyncProgress(
                                relativePath = relativePath,
                                processedItems = doneFiles,
                                totalItems = plans.size,
                                currentReadBytes = remoteBytes,
                                currentTotalBytes = remoteBytes,
                                transferredBytes = transferredBytes(),
                                totalBytes = transferStats.totalBytes,
                            )
                        )
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    cancellationToken?.throwIfCancelled()
                    activeBytes.remove(relativePath)
                    val error = "Failed to download $relativePath: ${e.describe()}"
                    errorsMutex.withLock {
                        errors.add(error)
                    }
                    syncRepository.markFileError(taskId, relativePath, error)
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            errors.add(it.describe())
        }

        if (errors.isEmpty() && rootService.exists(rootDir.path)) {
            rootService.walkFileTree(rootDir.path).map { it.pathString }.distinct().forEach { localPath ->
                val relativePath = localRelativePath(localPath, rootDir.path)
                if (relativePath !in remoteRelativePaths) {
                    runCatching { File(localPath).delete() || rootService.deleteRecursively(localPath) }
                        .onFailure { errors.add("Failed to delete local stale file $relativePath: ${it.describe()}") }
                }
            }
        }
        if (errors.isEmpty()) {
            runCatching {
                val finalLocalSnapshot = rebuildLocalRepositoryMeta(localRepositoryRoot, cancellationToken = cancellationToken)
                withClient(cloud, cancellationToken = cancellationToken) { client, _ ->
                    writeRemoteRepositoryMeta(
                        client = client,
                        remoteRoot = remoteRepositoryRoot,
                        files = finalLocalSnapshot.files,
                        cancellationToken = cancellationToken,
                    )
                }
            }.onFailure {
                errors.add("Failed to update repository meta after pull: ${it.describe()}")
            }
        }

        val result = RepositorySyncResult(stats = stats, transferStats = transferStats, errors = errors)
        log { "Force pull finished: success=${result.isSuccess}, files=${result.stats.fileCount}, errors=${result.errors.size}" }
        logRepositoryErrors("Force pull", result.errors)
        syncTaskId?.let { syncRepository.markTaskFinished(it, result) }
        return result
    }

    suspend fun uploadPending(
        cloud: String? = null,
        includeFailed: Boolean = false,
        onUploading: (path: String, read: Long, total: Long) -> Unit = { _, _, _ -> },
    ): ShellResult = run {
        migrateLegacyPendingUploads()
        val out = mutableListOf<String>()
        val outMutex = Mutex()
        var failedCount = 0
        val items = syncRepository.queryPendingPushFiles(cloud = cloud, includeFailed = includeFailed)
        val semaphore = Semaphore(syncConcurrency())
        withContext(Dispatchers.IO) {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            withClient(item.cloud) { client, _ ->
                                upload(
                                    client = client,
                                    src = item.localPath,
                                    dstDir = item.remoteDir,
                                    deleteAfterUploaded = false,
                                    skipIfSameSize = false,
                                    onUploading = { read, total -> onUploading(item.localPath, read, total) },
                                ).also { result ->
                                    outMutex.withLock { out.addAll(result.out) }
                                    if (result.isSuccess.not()) {
                                        outMutex.withLock { failedCount += 1 }
                                        syncRepository.markFileError(item.syncTaskId, item.relativePath, result.outString)
                                    } else {
                                        syncRepository.markFileDone(item.syncTaskId, item.relativePath, item.bytes)
                                    }
                                }
                            }
                        }.onFailure {
                            val stringWriter = StringWriter()
                            val printWriter = PrintWriter(stringWriter)
                            it.printStackTrace(printWriter)
                            outMutex.withLock {
                                failedCount += 1
                                out.add(log { stringWriter.toString() })
                            }
                            syncRepository.markFileError(item.syncTaskId, item.relativePath, stringWriter.toString())
                        }
                    }
                }
            }.forEach { it.await() }
        }
        ShellResult(code = if (failedCount == 0) 0 else -1, input = listOf(), out = out)
    }

    suspend fun download(
        client: CloudClient,
        src: String,
        dstDir: String,
        deleteAfterDownloaded: Boolean = true,
        onDownloading: (written: Long, total: Long) -> Unit = { _, _ -> },
        onDownloaded: suspend (path: String) -> Unit,
    ): ShellResult =
        run {
            log { "Downloading..." }

            var code = 0
            val out = mutableListOf<String>()
            rootService.deleteRecursively(dstDir)
            rootService.mkdirs(dstDir)
            PathUtil.setFilesDirSELinux(context)

            runCatching {
                client.download(src = src, dst = dstDir, onDownloading = onDownloading)
            }.onFailure {
                code = -2
                if (it.localizedMessage != null)
                    out.add(log { it.localizedMessage!! })
            }

            if (code == 0) {
                onDownloaded("$dstDir/${PathUtil.getFileName(src)}")
            } else {
                out.add(log { "Failed to download $src." })
            }
            if (deleteAfterDownloaded)
                rootService.deleteRecursively(dstDir).also { result ->
                    code = if (result) code else -1
                    if (result.not()) out.add(log { "Failed to delete $dstDir." })
                }

            ShellResult(code = code, input = listOf(), out = out)
        }

    suspend fun getClient(name: String? = null): Pair<CloudClient, CloudEntity> {
        val entity = queryByName(name ?: context.readCloudActivatedAccountName().first())
        if (entity != null) if (entity.remote.isEmpty()) throw IllegalAccessException("${entity.name}: Remote directory is not set.")
        val client = entity?.getCloud()?.apply { connect() } ?: throw NullPointerException("Client is null.")
        return client to entity
    }

    suspend fun <T> withClient(
        name: String? = null,
        cancellationToken: RepositorySyncCancellationToken? = null,
        block: suspend (client: CloudClient, entity: CloudEntity) -> T,
    ): T = run {
        val entity = queryByName(name ?: context.readCloudActivatedAccountName().first())
        if (entity != null) if (entity.remote.isEmpty()) throw IllegalAccessException("${entity.name}: Remote directory is not set.")
        val client = entity?.getCloud() ?: throw NullPointerException("Client is null.")
        cancellationToken?.register(client)
        try {
            cancellationToken?.throwIfCancelled()
            client.connect()
            cancellationToken?.throwIfCancelled()
            block(client, entity)
        } finally {
            cancellationToken?.unregister(client)
            runCatching { client.disconnect() }
        }
    }

    suspend fun withActivatedClients(block: suspend (clients: List<Pair<CloudClient, CloudEntity>>) -> Unit) = run {
        val clients: MutableList<Pair<CloudClient, CloudEntity>> = mutableListOf()
        cloudDao.queryActivated().forEach {
            if (it.remote.isEmpty()) throw IllegalAccessException("${it.name}: Remote directory is not set.")
            clients.add(it.getCloud().apply { connect() } to it)
        }
        block(clients)
        clients.forEach { it.first.disconnect() }
    }

    private suspend fun syncConcurrency(): Int = context.readSyncConcurrency().first().coerceIn(1, 16)

    private suspend fun processTaskConcurrently(
        syncTaskId: Long,
        direction: SyncDirection,
        cloud: String,
        totalFiles: Int,
        cancellationToken: RepositorySyncCancellationToken? = null,
        processFile: suspend (client: CloudClient, file: SyncFileTaskEntity, workerIndex: Int) -> Unit,
    ) {
        if (totalFiles == 0) return
        cancellationToken?.throwIfCancelled()
        val workerCount = minOf(syncConcurrency(), totalFiles)
        withContext(Dispatchers.IO) {
            val semaphore = Semaphore(workerCount)
            val jobs = (0 until workerCount).map { workerIndex ->
                async {
                    semaphore.withPermit {
                        withClient(cloud, cancellationToken = cancellationToken) { client, _ ->
                            while (true) {
                                cancellationToken?.throwIfCancelled()
                                val batch = syncRepository.claimPendingFileTasks(
                                    syncTaskId = syncTaskId,
                                    direction = direction,
                                    limit = 1,
                                )
                                val file = batch.firstOrNull() ?: break
                                cancellationToken?.throwIfCancelled()
                                processFile(client, file, workerIndex)
                            }
                        }
                    }
                }
            }
            jobs.forEach { it.await() }
        }
    }

    private suspend fun rebuildLocalRepositoryMeta(
        repoRoot: String,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): RepositoryFileSnapshot {
        cancellationToken?.throwIfCancelled()
        if (rootService.exists(repoRoot.trimEnd('/')).not()) {
            return RepositoryFileSnapshot()
        }
        val files = localRepositoryFiles(repoRoot = repoRoot, calculateSha256 = true, cancellationToken = cancellationToken)
        val metaFiles = files.toRepositoryMetaFiles()
        writeLocalRepositoryMeta(repoRoot, metaFiles)
        return RepositoryFileSnapshot(files = metaFiles, updatedAt = DateUtil.getTimestamp())
    }

    private suspend fun readLocalRepositoryMeta(repoRoot: String): RepositoryFileSnapshot? = runCatching {
        val layout = RepositoryLayout.fromRoot(parentRootOfRepository(repoRoot))
        log { "RepositoryMeta local read start: repoRoot=$repoRoot expectedRoot=${layout.repositoryRoot} meta=${layout.metaPath}" }
        if (rootService.exists(layout.repositoryRoot).not()) {
            log { "RepositoryMeta local read skipped: repository root does not exist: ${layout.repositoryRoot}" }
            return@runCatching null
        }
        rootService.setAllPermissions(layout.repositoryRoot)
        if (rootService.exists(layout.metaPath).not()) {
            log { "RepositoryMeta local read skipped: meta file does not exist: ${layout.metaPath}" }
            return@runCatching null
        }
        relaxLocalFileForAppRead(root = layout.repositoryRoot, path = layout.metaPath)
        val json = rootService.readText(layout.metaPath)
        log { "RepositoryMeta local file read: path=${layout.metaPath} chars=${json.length}" }
        if (json.isBlank()) {
            log { "RepositoryMeta local read rejected: blank meta file: ${layout.metaPath}" }
            return@runCatching null
        }
        parseRepositoryMeta(json = json, expectedRoot = layout.repositoryRoot, source = "local:${layout.metaPath}")
    }.getOrElse {
        log { "Failed to read local repository meta for $repoRoot: ${it.describe()}" }
        null
    }

    private suspend fun writeLocalRepositoryMeta(repoRoot: String, files: List<RepositoryMetaFile>) {
        val layout = RepositoryLayout.fromRoot(parentRootOfRepository(repoRoot))
        rootService.mkdirs(layout.repositoryRoot)
        check(rootService.writeText(text = buildRepositoryMetaJson(layout.repositoryRoot, files), dst = layout.metaPath)) {
            "Failed to write local repository meta: ${layout.metaPath}"
        }
        rootService.setAllPermissions(layout.repositoryRoot)
    }

    private fun readRemoteRepositoryMeta(client: CloudClient, remoteRoot: String): RepositoryFileSnapshot? = runCatching {
        val metaPath = "${remoteRoot.trimEnd('/')}/${RepositoryLayout.META_FILE}"
        log { "RepositoryMeta remote read start: remoteRoot=$remoteRoot meta=$metaPath" }
        if (client.exists(metaPath).not()) {
            log { "RepositoryMeta remote read skipped: meta file does not exist: $metaPath" }
            return@runCatching null
        }
        val tempDir = File(context.cacheDir, "repository-meta/${remoteRoot.hashCode() and 0x7FFFFFFF}-${DateUtil.getTimestamp()}-${Thread.currentThread().id}").apply { mkdirs() }
        try {
            client.download(src = metaPath, dst = tempDir.absolutePath) { _, _ -> }
            val metaFile = File(tempDir, RepositoryLayout.META_FILE)
            if (metaFile.exists().not()) {
                log { "RepositoryMeta remote read rejected: downloaded meta missing from temp dir: ${metaFile.absolutePath}" }
                return@runCatching null
            }
            val json = metaFile.readText()
            log { "RepositoryMeta remote file read: path=$metaPath chars=${json.length}" }
            parseRepositoryMeta(
                json = json,
                expectedRoot = remoteRoot.trimEnd('/'),
                source = "remote:$metaPath",
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }.getOrElse {
        log { "Failed to read remote repository meta for $remoteRoot: ${it.describe()}" }
        null
    }

    private suspend fun rebuildRemoteRepositoryMeta(
        client: CloudClient,
        remoteRoot: String,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): RepositoryFileSnapshot {
        val files = scanRemoteRepositoryFiles(client = client, remoteRoot = remoteRoot, cancellationToken = cancellationToken)
        writeRemoteRepositoryMeta(client = client, remoteRoot = remoteRoot, files = files, cancellationToken = cancellationToken)
        return RepositoryFileSnapshot(files = files, updatedAt = DateUtil.getTimestamp())
    }

    private fun scanRemoteRepositoryFiles(
        client: CloudClient,
        remoteRoot: String,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): List<RepositoryMetaFile> {
        cancellationToken?.throwIfCancelled()
        return client.walkFileTree(remoteRoot)
            .filterNot { isPartialSyncFile(it.pathString) }
            .mapNotNull { remoteFile ->
                cancellationToken?.throwIfCancelled()
                val relativePath = normalizeRelativePathOrNull(remoteRelativePath(remotePath = remoteFile.pathString, remoteRoot = remoteRoot))
                if (relativePath == null || relativePath == RepositoryLayout.META_FILE) {
                    null
                } else {
                    RepositoryMetaFile(
                        relativePath = relativePath,
                        bytes = runCatching { client.size(remoteFile.pathString) }.getOrDefault(0L),
                        sha256 = "",
                    )
                }
            }
            .sortedBy { it.relativePath }
    }

    private suspend fun writeRemoteRepositoryMeta(
        client: CloudClient,
        remoteRoot: String,
        files: List<RepositoryMetaFile>,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ) {
        val tempDir = File(context.cacheDir, "repository-meta-upload/${remoteRoot.hashCode() and 0x7FFFFFFF}-${DateUtil.getTimestamp()}-${Thread.currentThread().id}")
        val tempFile = File(tempDir, RepositoryLayout.META_FILE)
        try {
            tempDir.mkdirs()
            tempFile.writeText(buildRepositoryMetaJson(remoteRoot.trimEnd('/'), files))
            uploadFileAtomically(
                client = client,
                src = tempFile.absolutePath,
                dstDir = remoteRoot,
                onUploading = { _, _ -> },
                skipIfSameSize = false,
                cancellationToken = cancellationToken,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun parseRepositoryMeta(json: String, expectedRoot: String, source: String): RepositoryFileSnapshot? {
        val meta = runCatching { gson.fromJson<RepositoryMeta>(json, RepositoryMeta::class.java) }.getOrElse {
            log { "RepositoryMeta rejected: source=$source reason=invalid_json error=${it.describe()}" }
            return null
        } ?: run {
            log { "RepositoryMeta rejected: source=$source reason=null_json" }
            return null
        }
        if (meta.version != REPOSITORY_META_VERSION) {
            log { "RepositoryMeta rejected: source=$source reason=version_mismatch actual=${meta.version} expected=$REPOSITORY_META_VERSION" }
            return null
        }
        val actualRoot = meta.repositoryRoot.trimEnd('/')
        val normalizedExpectedRoot = expectedRoot.trimEnd('/')
        if (actualRoot != normalizedExpectedRoot) {
            log { "RepositoryMeta rejected: source=$source reason=root_mismatch actual=$actualRoot expected=$normalizedExpectedRoot" }
            return null
        }
        val parsedFiles = mutableListOf<RepositoryMetaFile>()
        meta.files.forEachIndexed { index, file ->
            val relativePath = normalizeRelativePathOrNull(file.relativePath) ?: run {
                log { "RepositoryMeta rejected: source=$source reason=invalid_relative_path index=$index value=${file.relativePath}" }
                return null
            }
            parsedFiles.add(
                RepositoryMetaFile(
                    relativePath = relativePath,
                    bytes = file.bytes.coerceAtLeast(0L),
                    sha256 = normalizeSha256OrEmpty(file.sha256),
                )
            )
        }
        val files = parsedFiles.distinctBy { it.relativePath }.sortedBy { it.relativePath }
        val fileCountMatches = meta.fileCount == files.size
        val totalBytes = files.sumOf { it.bytes }
        val totalBytesMatches = meta.totalBytes == totalBytes
        if (fileCountMatches.not()) {
            log { "RepositoryMeta rejected: source=$source reason=file_count_mismatch actual=${meta.fileCount} parsed=${files.size} raw=${parsedFiles.size}" }
            return null
        }
        if (totalBytesMatches.not()) {
            log { "RepositoryMeta rejected: source=$source reason=total_bytes_mismatch actual=${meta.totalBytes} parsed=$totalBytes" }
            return null
        }
        val snapshot = RepositoryFileSnapshot(files = files, updatedAt = meta.generatedAt)
        log { "RepositoryMeta accepted: source=$source files=${snapshot.stats.fileCount} bytes=${snapshot.stats.totalBytes} updatedAt=${snapshot.updatedAt}" }
        return snapshot
    }

    private fun buildRepositoryMetaJson(repositoryRoot: String, files: List<RepositoryMetaFile>): String {
        val normalizedFiles = files
            .mapNotNull {
                val relativePath = normalizeRelativePathOrNull(it.relativePath) ?: return@mapNotNull null
                RepositoryMetaFile(
                    relativePath = relativePath,
                    bytes = it.bytes.coerceAtLeast(0L),
                    sha256 = normalizeSha256OrEmpty(it.sha256),
                )
            }
            .distinctBy { it.relativePath }
            .sortedBy { it.relativePath }
        return gson.toJson(
            RepositoryMeta(
                version = REPOSITORY_META_VERSION,
                repositoryRoot = repositoryRoot.trimEnd('/'),
                generatedAt = DateUtil.getTimestamp(),
                fileCount = normalizedFiles.size,
                totalBytes = normalizedFiles.sumOf { it.bytes },
                files = normalizedFiles,
            )
        )
    }

    private fun List<LocalRepositoryFile>.toRepositoryMetaFiles(): List<RepositoryMetaFile> {
        return mapNotNull {
            val relativePath = normalizeRelativePathOrNull(it.relativePath) ?: return@mapNotNull null
            RepositoryMetaFile(
                relativePath = relativePath,
                bytes = it.bytes,
                sha256 = normalizeSha256OrEmpty(it.sha256),
            )
        }.sortedBy { it.relativePath }
    }

    private fun normalizeRelativePath(relativePath: String): String = relativePath.replace('\\', '/').trim('/')

    private fun normalizeRelativePathOrNull(relativePath: String): String? {
        val normalized = normalizeRelativePath(relativePath)
        if (normalized.isEmpty()) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return null
        if (parts.joinToString("/") != normalized) return null
        return normalized
    }

    private fun normalizeSha256OrEmpty(value: String): String {
        val normalized = value.trim().lowercase()
        return if (normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) normalized else ""
    }

    private fun RepositoryMetaFile?.hasSameContentAs(localFile: LocalRepositoryFile): Boolean {
        if (this == null || sha256.isBlank() || localFile.sha256.isBlank()) return false
        return bytes == localFile.bytes && sha256 == localFile.sha256
    }

    private fun RepositoryMetaFile?.hasSameContentAs(remoteFile: RepositoryMetaFile): Boolean {
        if (this == null || sha256.isBlank() || remoteFile.sha256.isBlank()) return false
        return bytes == remoteFile.bytes && sha256 == remoteFile.sha256
    }

    private fun LocalRepositoryFile?.hasSameContentAs(remoteFile: RepositoryMetaFile): Boolean {
        if (this == null || sha256.isBlank() || remoteFile.sha256.isBlank()) return false
        return bytes == remoteFile.bytes && sha256 == remoteFile.sha256
    }

    private fun parentRootOfRepository(repositoryRoot: String): String {
        val normalized = repositoryRoot.replace('\\', '/').trimEnd('/')
        return normalized.removeSuffix("/${RepositoryLayout.REPOSITORY_DIR}")
    }

    private fun remotePath(remoteRoot: String, relativePath: String): String {
        val normalized = normalizeRelativePath(relativePath)
        return if (normalized.isEmpty()) remoteRoot.trimEnd('/') else "${remoteRoot.trimEnd('/')}/$normalized"
    }

    private fun clearKnownEmptyRemoteParents(client: CloudClient, remoteRoot: String, relativePaths: List<String>) {
        val directories = relativePaths
            .map { PathUtil.getParentPath(normalizeRelativePath(it)) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
        directories.flatMap { directory ->
            val parents = mutableListOf<String>()
            var current = directory
            while (current.isNotEmpty()) {
                parents.add(current)
                current = PathUtil.getParentPath(current)
            }
            parents
        }.distinct().sortedByDescending { it.length }.forEach { parent ->
            runCatching { client.removeDirectory(remotePath(remoteRoot = remoteRoot, relativePath = parent)) }
        }
    }

    private suspend fun sha256(path: String, cancellationToken: RepositorySyncCancellationToken? = null): String {
        cancellationToken?.throwIfCancelled()
        val tempDir = File(
            context.cacheDir,
            "repository-sha256/${path.hashCode() and 0x7FFFFFFF}-${DateUtil.getTimestamp()}-${Thread.currentThread().id}"
        )
        val stagedFile = File(tempDir, "source")
        try {
            tempDir.mkdirs()
            check(rootService.copyTo(path, stagedFile.absolutePath, overwrite = true)) {
                "Failed to stage local file for sha256: $path"
            }
            rootService.setAllPermissions(stagedFile.absolutePath)
            cancellationToken?.throwIfCancelled()
            return sha256(stagedFile, cancellationToken)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun sha256(file: File, cancellationToken: RepositorySyncCancellationToken? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                cancellationToken?.throwIfCancelled()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun localRepositoryFiles(
        repoRoot: String,
        calculateSha256: Boolean,
        cancellationToken: RepositorySyncCancellationToken? = null,
    ): List<LocalRepositoryFile> {
        cancellationToken?.throwIfCancelled()
        val root = repoRoot.trimEnd('/')
        if (rootService.exists(root).not()) {
            log { "Local repository root does not exist: $root" }
            return emptyList()
        }
        rootService.setAllPermissions(root)
        val files = rootService.walkFileTree(root)
            .map { it.pathString }
            .distinct()
            .filterNot { isPartialSyncFile(it) }
            .mapNotNull { path ->
                cancellationToken?.throwIfCancelled()
                val relativePath = normalizeRelativePathOrNull(localRelativePath(path, root))
                if (relativePath == null || relativePath == RepositoryLayout.META_FILE) {
                    null
                } else {
                    relaxLocalFileForAppRead(root = root, path = path)
                    val bytes = rootService.calculateSize(path)
                    LocalRepositoryFile(
                        relativePath = relativePath,
                        path = path,
                        bytes = bytes,
                        sha256 = if (calculateSha256) sha256(path, cancellationToken) else "",
                    )
                }
            }
        log { "Enumerated local repository files via root service: root=$root files=${files.size}" }
        return files
    }

    private suspend fun relaxLocalFileForAppRead(root: String, path: String) {
        val normalizedRoot = root.trimEnd('/')
        var current = PathUtil.getParentPath(path)
        val parents = mutableListOf<String>()
        while (current.startsWith(normalizedRoot) && current != normalizedRoot && current.isNotEmpty()) {
            parents.add(current)
            current = PathUtil.getParentPath(current)
        }
        parents.asReversed().forEach { rootService.setAllPermissions(it) }
        rootService.setAllPermissions(path)
    }

    private fun remoteParentDir(remoteRoot: String, relativePath: String): String {
        val parent = PathUtil.getParentPath(relativePath)
        return if (parent.isEmpty()) remoteRoot else "${remoteRoot.trimEnd('/')}/$parent"
    }

    private fun remoteRelativePath(remotePath: String, remoteRoot: String): String {
        val root = remoteRoot.trimEnd('/')
        return remotePath.trim('/').removePrefix(root.trim('/')).trim('/')
    }

    private fun localRelativePath(localPath: String, localRoot: String): String {
        val root = localRoot.trimEnd('/').replace('\\', '/')
        return localPath.replace('\\', '/').trim('/').removePrefix(root.trim('/')).trim('/')
    }

    private fun localParentDir(rootDir: File, relativePath: String): File {
        val parent = PathUtil.getParentPath(relativePath)
        return if (parent.isEmpty()) rootDir else File(rootDir, parent)
    }

    private fun isPartialSyncFile(path: String): Boolean = PathUtil.getFileName(path).endsWith(".partial")

    private fun logRepositoryErrors(operation: String, errors: List<String>) {
        errors.forEachIndexed { index, error ->
            log { "$operation error ${index + 1}/${errors.size}: $error" }
        }
    }

    private suspend fun migrateLegacyPendingUploads() {
        val queueFile = File(context.filesDir, "pending_uploads.json")
        if (queueFile.exists().not()) return
        runCatching {
            val queue = gson.fromJson<PendingUploadQueue>(queueFile.readText(), object : TypeToken<PendingUploadQueue>() {}.type)
            queue.items.forEach { item ->
                val file = File(item.localPath)
                syncRepository.createTask(
                    direction = SyncDirection.PUSH,
                    cloud = item.cloud,
                    localRoot = PathUtil.getParentPath(item.localPath),
                    remoteRoot = item.remoteDir,
                    files = listOf(
                        SyncFilePlan(
                            relativePath = file.name,
                            localPath = item.localPath,
                            remotePath = "${item.remoteDir}/${file.name}",
                            remoteDir = item.remoteDir,
                            bytes = file.length(),
                        )
                    ),
                )
            }
            queueFile.delete()
        }.onFailure {
            log { "Failed to migrate pending_uploads.json: ${it.describe()}" }
        }
    }

    private fun Throwable.describe(): String = localizedMessage ?: message ?: toString()
}
