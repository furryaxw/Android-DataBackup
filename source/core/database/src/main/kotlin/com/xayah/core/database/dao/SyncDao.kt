package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.xayah.core.model.CloudSyncState
import com.xayah.core.model.SyncDirection
import com.xayah.core.model.database.SyncFileTaskEntity
import com.xayah.core.model.database.SyncTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Upsert(entity = SyncTaskEntity::class)
    suspend fun upsert(item: SyncTaskEntity): Long

    @Upsert(entity = SyncFileTaskEntity::class)
    suspend fun upsertFiles(items: List<SyncFileTaskEntity>)

    @Query("SELECT * FROM SyncTaskEntity ORDER BY updatedAt DESC LIMIT 1")
    fun queryLatestTaskFlow(): Flow<SyncTaskEntity?>

    @Query("SELECT * FROM SyncTaskEntity WHERE id = :id LIMIT 1")
    suspend fun queryTask(id: Long): SyncTaskEntity?

    @Query("SELECT * FROM SyncFileTaskEntity WHERE syncTaskId = :syncTaskId ORDER BY id ASC")
    fun queryFilesFlow(syncTaskId: Long): Flow<List<SyncFileTaskEntity>>

    @Query("SELECT * FROM SyncFileTaskEntity WHERE syncTaskId = :syncTaskId ORDER BY id ASC")
    suspend fun queryFiles(syncTaskId: Long): List<SyncFileTaskEntity>

    @Query("SELECT * FROM SyncFileTaskEntity WHERE id in (:ids) ORDER BY id ASC")
    suspend fun queryFilesByIds(ids: List<Long>): List<SyncFileTaskEntity>

    @Query(
        "SELECT id FROM SyncFileTaskEntity WHERE syncTaskId = :syncTaskId AND state = :state ORDER BY id ASC LIMIT :limit"
    )
    suspend fun queryClaimableFileIds(syncTaskId: Long, state: CloudSyncState, limit: Int): List<Long>

    @Query(
        "UPDATE SyncFileTaskEntity SET state = :state, claimedAt = :now, updatedAt = :now WHERE id in (:ids)"
    )
    suspend fun markFilesClaimed(ids: List<Long>, state: CloudSyncState, now: Long)

    @Transaction
    suspend fun claimPendingFileTasks(syncTaskId: Long, direction: SyncDirection, limit: Int, now: Long): List<SyncFileTaskEntity> {
        val ids = queryClaimableFileIds(syncTaskId = syncTaskId, state = CloudSyncState.QUEUED, limit = limit)
        if (ids.isEmpty()) return emptyList()
        markFilesClaimed(
            ids = ids,
            state = if (direction == SyncDirection.PULL) CloudSyncState.DOWNLOADING else CloudSyncState.UPLOADING,
            now = now,
        )
        return queryFilesByIds(ids)
    }

    @Transaction
    suspend fun createTask(task: SyncTaskEntity, files: List<SyncFileTaskEntity>): Long {
        val taskId = upsert(task)
        deleteFiles(taskId)
        if (files.isNotEmpty()) {
            upsertFiles(files.map { it.copy(syncTaskId = taskId) })
        }
        return taskId
    }

    @Query(
        "SELECT * FROM SyncFileTaskEntity WHERE direction = :direction AND (:cloud IS NULL OR cloud = :cloud) AND state in (:states) ORDER BY updatedAt ASC"
    )
    suspend fun queryFileTasks(direction: SyncDirection, cloud: String?, states: List<CloudSyncState>): List<SyncFileTaskEntity>

    @Query("SELECT COUNT(*) FROM SyncFileTaskEntity WHERE state in (:states)")
    suspend fun countFilesByStates(states: List<CloudSyncState>): Int

    @Query("UPDATE SyncTaskEntity SET state = :state, updatedAt = :now, startedAt = :now WHERE id = :id")
    suspend fun markTaskStarted(id: Long, state: CloudSyncState, now: Long)

    @Query(
        "UPDATE SyncTaskEntity SET state = :state, processedFiles = :processedFiles, processedBytes = :processedBytes, failureCount = :failureCount, lastError = :lastError, updatedAt = :now, finishedAt = :now WHERE id = :id"
    )
    suspend fun markTaskFinished(
        id: Long,
        state: CloudSyncState,
        processedFiles: Int,
        processedBytes: Long,
        failureCount: Int,
        lastError: String,
        now: Long,
    )

    @Query(
        "UPDATE SyncTaskEntity SET processedFiles = :processedFiles, processedBytes = :processedBytes, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateTaskProgress(id: Long, processedFiles: Int, processedBytes: Long, now: Long)

    @Query(
        "UPDATE SyncFileTaskEntity SET state = :state, transferredBytes = :transferredBytes, updatedAt = :now WHERE syncTaskId = :syncTaskId AND relativePath = :relativePath"
    )
    suspend fun markFileProgress(syncTaskId: Long, relativePath: String, state: CloudSyncState, transferredBytes: Long, now: Long)

    @Query(
        "UPDATE SyncFileTaskEntity SET state = :state, transferredBytes = :bytes, updatedAt = :now, finishedAt = :now, lastError = '' WHERE syncTaskId = :syncTaskId AND relativePath = :relativePath"
    )
    suspend fun markFileDone(syncTaskId: Long, relativePath: String, state: CloudSyncState, bytes: Long, now: Long)

    @Query(
        "UPDATE SyncFileTaskEntity SET state = :state, attempts = attempts + 1, lastError = :error, updatedAt = :now WHERE syncTaskId = :syncTaskId AND relativePath = :relativePath"
    )
    suspend fun markFileError(syncTaskId: Long, relativePath: String, state: CloudSyncState, error: String, now: Long)

    @Query(
        "UPDATE SyncFileTaskEntity SET state = :queuedState, updatedAt = :now WHERE syncTaskId = :syncTaskId AND state = :errorState"
    )
    suspend fun resetFailedFiles(syncTaskId: Long, errorState: CloudSyncState, queuedState: CloudSyncState, now: Long)

    @Query("DELETE FROM SyncFileTaskEntity WHERE syncTaskId = :syncTaskId")
    suspend fun deleteFiles(syncTaskId: Long)
}
