package com.xayah.core.data.repository

import com.xayah.core.model.DataType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.DateUtil
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.PathUtil
import java.io.File

enum class RepositoryItemType {
    APP,
    FILE,
}

data class RepositoryManifest(
    val version: Int = 1,
    val updatedAt: Long = 0,
    val items: List<RepositoryManifestItem> = emptyList(),
)

data class RepositoryManifestItem(
    val id: String = "",
    val type: RepositoryItemType = RepositoryItemType.FILE,
    val name: String = "",
    val repositoryRelativePath: String = "",
    val updatedAt: Long = 0,
)

data class RepositoryItemManifest(
    val version: Int = 1,
    val id: String = "",
    val type: RepositoryItemType = RepositoryItemType.FILE,
    val name: String = "",
    val sourcePath: String = "",
    val packageName: String = "",
    val userId: Int = 0,
    val preserveId: Long = 0,
    val repositoryRelativePath: String = "",
    val snapshots: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0,
)

class BackupCatalog(
    private val gson: GsonUtil = GsonUtil(),
    private val rootService: RemoteRootService? = null,
) {
    suspend fun readManifest(layout: RepositoryLayout): RepositoryManifest = readManifest(layout.manifestPath)

    suspend fun readItemManifest(layout: RepositoryLayout, item: RepositoryManifestItem): RepositoryItemManifest {
        return readItemManifest("${layout.repositoryRoot}/${item.repositoryRelativePath}/${RepositoryLayout.ITEM_MANIFEST_FILE}")
    }

    suspend fun readItemManifests(layout: RepositoryLayout): List<RepositoryItemManifest> {
        val itemsById = linkedMapOf<String, RepositoryItemManifest>()
        readManifest(layout).items.forEach { item ->
            val manifest = readItemManifest(layout, item).takeIf { it.id.isNotEmpty() }
            if (manifest != null) {
                itemsById[manifest.id] = manifest
            }
        }
        return itemsById.values.toList()
    }

    suspend fun recordPackageSnapshot(
        layout: RepositoryLayout,
        packageEntity: PackageEntity,
        repositoryPath: String,
        dataType: DataType,
        snapshotId: String,
    ) {
        val now = DateUtil.getTimestamp()
        val id = packageItemId(packageEntity.packageName, packageEntity.userId, packageEntity.preserveId)
        val relativePath = layout.relativeRepositoryPath(repositoryPath)
        val previous = readItemManifest(layout.itemManifestPath(repositoryPath))
        val snapshots = previous.snapshots.toMutableMap().apply { put(dataType.name, snapshotId) }
        val item = RepositoryItemManifest(
            id = id,
            type = RepositoryItemType.APP,
            name = packageEntity.packageInfo.label,
            packageName = packageEntity.packageName,
            userId = packageEntity.userId,
            preserveId = packageEntity.preserveId,
            repositoryRelativePath = relativePath,
            snapshots = snapshots,
            updatedAt = now,
        )
        writeItemManifest(layout.itemManifestPath(repositoryPath), item)
        upsertManifestItem(
            layout = layout,
            item = RepositoryManifestItem(
                id = id,
                type = RepositoryItemType.APP,
                name = packageEntity.packageInfo.label,
                repositoryRelativePath = relativePath,
                updatedAt = now,
            )
        )
    }

    suspend fun recordMediaSnapshot(
        layout: RepositoryLayout,
        mediaEntity: MediaEntity,
        repositoryPath: String,
        snapshotId: String,
    ) {
        val now = DateUtil.getTimestamp()
        val id = fileItemId(mediaEntity.name, mediaEntity.preserveId)
        val relativePath = layout.relativeRepositoryPath(repositoryPath)
        val item = RepositoryItemManifest(
            id = id,
            type = RepositoryItemType.FILE,
            name = mediaEntity.name,
            sourcePath = mediaEntity.path,
            preserveId = mediaEntity.preserveId,
            repositoryRelativePath = relativePath,
            snapshots = mapOf(DataType.MEDIA_MEDIA.name to snapshotId),
            updatedAt = now,
        )
        writeItemManifest(layout.itemManifestPath(repositoryPath), item)
        upsertManifestItem(
            layout = layout,
            item = RepositoryManifestItem(
                id = id,
                type = RepositoryItemType.FILE,
                name = mediaEntity.name,
                repositoryRelativePath = relativePath,
                updatedAt = now,
            )
        )
    }

    private suspend fun readManifest(path: String): RepositoryManifest = runCatching {
        readText(path)?.let { gson.fromJson(it, RepositoryManifest::class.java) } ?: RepositoryManifest()
    }.getOrElse { RepositoryManifest() }

    private suspend fun readItemManifest(path: String): RepositoryItemManifest = runCatching {
        readText(path)?.let { gson.fromJson(it, RepositoryItemManifest::class.java) } ?: RepositoryItemManifest()
    }.getOrElse { RepositoryItemManifest() }

    private suspend fun writeItemManifest(path: String, item: RepositoryItemManifest) {
        writeTextOrThrow(path, gson.toJson(item))
    }

    private suspend fun upsertManifestItem(layout: RepositoryLayout, item: RepositoryManifestItem) {
        val now = DateUtil.getTimestamp()
        val manifest = readManifest(layout.manifestPath)
        val items = manifest.items.filterNot { it.id == item.id }.toMutableList()
        items.add(item)
        writeTextOrThrow(layout.manifestPath, gson.toJson(manifest.copy(updatedAt = now, items = items.sortedBy { it.repositoryRelativePath })))
    }

    private suspend fun readText(path: String): String? {
        if (rootService != null) {
            if (rootService.exists(path).not()) return null
            return rootService.readText(path)
        }
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }

    private suspend fun writeTextOrThrow(path: String, text: String) {
        val success = if (rootService != null) {
            rootService.writeText(text = text, dst = path).also {
                if (it) rootService.setAllPermissions(PathUtil.getParentPath(path))
            }
        } else {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(text)
            true
        }
        if (success.not()) {
            throw IllegalStateException("Failed to write repository catalog: $path")
        }
    }

    private fun packageItemId(packageName: String, userId: Int, preserveId: Long): String {
        return "app:$packageName:user_$userId${if (preserveId == 0L) "" else "@$preserveId"}"
    }

    private fun fileItemId(name: String, preserveId: Long): String {
        return "file:$name${if (preserveId == 0L) "" else "@$preserveId"}"
    }
}
