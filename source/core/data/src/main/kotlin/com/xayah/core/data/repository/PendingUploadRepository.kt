package com.xayah.core.data.repository

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.xayah.core.model.backup.PendingUpload
import com.xayah.core.model.backup.PendingUploadQueue
import com.xayah.core.util.DateUtil
import com.xayah.core.util.GsonUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class PendingUploadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = GsonUtil()
    private val queueFile: File
        get() = File(context.filesDir, "pending_uploads.json")

    private fun readQueue(): PendingUploadQueue = runCatching {
        if (queueFile.exists().not()) return PendingUploadQueue()
        gson.fromJson<PendingUploadQueue>(queueFile.readText(), object : TypeToken<PendingUploadQueue>() {}.type)
    }.getOrElse { PendingUploadQueue() }

    private fun writeQueue(queue: PendingUploadQueue) {
        queueFile.parentFile?.mkdirs()
        queueFile.writeText(gson.toJson(queue))
    }

    fun query(): List<PendingUpload> = readQueue().items

    fun query(cloud: String): List<PendingUpload> = readQueue().items.filter { it.cloud == cloud }

    fun upsert(cloud: String, localPath: String, remoteDir: String, error: String = "") {
        val now = DateUtil.getTimestamp()
        val id = "$cloud|$localPath|$remoteDir"
        val items = readQueue().items.toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index == -1) {
            items.add(
                PendingUpload(
                    id = id,
                    cloud = cloud,
                    localPath = localPath,
                    remoteDir = remoteDir,
                    createdAt = now,
                    updatedAt = now,
                    attempts = if (error.isEmpty()) 0 else 1,
                    lastError = error,
                )
            )
        } else {
            val item = items[index]
            items[index] = item.copy(
                updatedAt = now,
                attempts = if (error.isEmpty()) item.attempts else item.attempts + 1,
                lastError = error.ifEmpty { item.lastError },
            )
        }
        writeQueue(PendingUploadQueue(items = items))
    }

    fun remove(cloud: String, localPath: String, remoteDir: String) {
        val id = "$cloud|$localPath|$remoteDir"
        writeQueue(PendingUploadQueue(items = readQueue().items.filterNot { it.id == id }))
    }
}
