package com.xayah.core.model.backup

import kotlinx.serialization.Serializable

@Serializable
data class PendingUpload(
    val id: String,
    val cloud: String,
    val localPath: String,
    val remoteDir: String,
    val createdAt: Long,
    val updatedAt: Long,
    val attempts: Int = 0,
    val lastError: String = "",
)

@Serializable
data class PendingUploadQueue(
    val items: List<PendingUpload> = emptyList(),
)
