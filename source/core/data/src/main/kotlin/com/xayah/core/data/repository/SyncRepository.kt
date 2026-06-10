package com.xayah.core.data.repository

import com.xayah.core.database.dao.SyncDao
import com.xayah.core.model.CloudSyncState
import com.xayah.core.model.SyncDirection
import com.xayah.core.model.database.SyncFileTaskEntity
import com.xayah.core.model.database.SyncTaskEntity
import com.xayah.core.util.DateUtil
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

data class SyncFilePlan(
    val relativePath: String,
    val localPath: String,
    val remotePath: String,
    val remoteDir: String,
    val bytes: Long,
)

class SyncRepository @Inject constructor(
    private val syncDao: SyncDao,
) {
    val latestTaskFlow: Flow<SyncTaskEntity?> = syncDao.queryLatestTaskFlow()

    fun queryFilesFlow(syncTaskId: Long): Flow<List<SyncFileTaskEntity>> = syncDao.queryFilesFlow(syncTaskId)

    suspend fun countPendingFiles(): Int = syncDao.countFilesByStates(listOf(CloudSyncState.QUEUED, CloudSyncState.ERROR))

    suspend fun createTask(
        direction: SyncDirection,
        cloud: String,
        localRoot: String,
        remoteRoot: String,
        files: List<SyncFilePlan>,
    ): Long {
        val now = DateUtil.getTimestamp()
        return syncDao.createTask(
            task = SyncTaskEntity(
                direction = direction,
                cloud = cloud,
                localRoot = localRoot,
                remoteRoot = remoteRoot,
                totalFiles = files.size,
                totalBytes = files.sumOf { it.bytes },
                createdAt = now,
                updatedAt = now,
            ),
            files = files.map { file ->
                SyncFileTaskEntity(
                    syncTaskId = 0,
                    direction = direction,
                    cloud = cloud,
                    relativePath = file.relativePath,
                    localPath = file.localPath,
                    remotePath = file.remotePath,
                    remoteDir = file.remoteDir,
                    bytes = file.bytes,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
    }

    suspend fun markTaskStarted(syncTaskId: Long, direction: SyncDirection) {
        syncDao.markTaskStarted(
            id = syncTaskId,
            state = if (direction == SyncDirection.PULL) CloudSyncState.DOWNLOADING else CloudSyncState.UPLOADING,
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun claimPendingFileTasks(syncTaskId: Long, direction: SyncDirection, limit: Int): List<SyncFileTaskEntity> =
        syncDao.claimPendingFileTasks(
            syncTaskId = syncTaskId,
            direction = direction,
            limit = limit,
            now = DateUtil.getTimestamp(),
        )

    suspend fun updateTaskProgress(syncTaskId: Long, processedFiles: Int, processedBytes: Long) {
        syncDao.updateTaskProgress(
            id = syncTaskId,
            processedFiles = processedFiles,
            processedBytes = processedBytes,
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun markTaskFinished(syncTaskId: Long, result: RepositorySyncResult) {
        syncDao.markTaskFinished(
            id = syncTaskId,
            state = if (result.isSuccess) CloudSyncState.DONE else CloudSyncState.ERROR,
            processedFiles = result.transferStats.fileCount - result.errors.size.coerceAtMost(result.transferStats.fileCount),
            processedBytes = result.transferStats.totalBytes,
            failureCount = result.errors.size,
            lastError = result.errors.joinToString("\n").take(16_000),
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun markFileProgress(syncTaskId: Long, relativePath: String, direction: SyncDirection, transferredBytes: Long) {
        syncDao.markFileProgress(
            syncTaskId = syncTaskId,
            relativePath = relativePath,
            state = if (direction == SyncDirection.PULL) CloudSyncState.DOWNLOADING else CloudSyncState.UPLOADING,
            transferredBytes = transferredBytes,
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun markFileDone(syncTaskId: Long, relativePath: String, bytes: Long) {
        syncDao.markFileDone(
            syncTaskId = syncTaskId,
            relativePath = relativePath,
            state = CloudSyncState.DONE,
            bytes = bytes,
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun markFileError(syncTaskId: Long, relativePath: String, error: String) {
        syncDao.markFileError(
            syncTaskId = syncTaskId,
            relativePath = relativePath,
            state = CloudSyncState.ERROR,
            error = error.take(16_000),
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun resetFailedFiles(syncTaskId: Long) {
        syncDao.resetFailedFiles(
            syncTaskId = syncTaskId,
            errorState = CloudSyncState.ERROR,
            queuedState = CloudSyncState.QUEUED,
            now = DateUtil.getTimestamp(),
        )
    }

    suspend fun queryPendingPushFiles(cloud: String? = null, includeFailed: Boolean = false): List<SyncFileTaskEntity> =
        syncDao.queryFileTasks(
            direction = SyncDirection.PUSH,
            cloud = cloud,
            states = if (includeFailed) {
                listOf(CloudSyncState.QUEUED, CloudSyncState.ERROR)
            } else {
                listOf(CloudSyncState.QUEUED)
            },
        )
}
