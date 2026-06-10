package com.xayah.core.model.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xayah.core.model.CloudSyncState
import com.xayah.core.model.SyncDirection

@Entity(
    indices = [
        Index(value = ["cloud", "state"]),
        Index(value = ["updatedAt"]),
    ]
)
data class SyncTaskEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var direction: SyncDirection,
    var cloud: String,
    var localRoot: String,
    var remoteRoot: String,
    var state: CloudSyncState = CloudSyncState.QUEUED,
    var totalFiles: Int = 0,
    var totalBytes: Long = 0,
    var processedFiles: Int = 0,
    var processedBytes: Long = 0,
    var failureCount: Int = 0,
    var lastError: String = "",
    var createdAt: Long,
    var updatedAt: Long,
    @ColumnInfo(defaultValue = "0") var startedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") var finishedAt: Long = 0,
)

@Entity(
    indices = [
        Index(value = ["syncTaskId", "state"]),
        Index(value = ["syncTaskId", "relativePath"], unique = true),
        Index(value = ["cloud", "state"]),
    ]
)
data class SyncFileTaskEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var syncTaskId: Long,
    var direction: SyncDirection,
    var cloud: String,
    var relativePath: String,
    var localPath: String,
    var remotePath: String,
    var remoteDir: String,
    var bytes: Long = 0,
    var transferredBytes: Long = 0,
    var state: CloudSyncState = CloudSyncState.QUEUED,
    var attempts: Int = 0,
    var lastError: String = "",
    var createdAt: Long,
    var updatedAt: Long,
    @ColumnInfo(defaultValue = "0") var claimedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") var finishedAt: Long = 0,
)
