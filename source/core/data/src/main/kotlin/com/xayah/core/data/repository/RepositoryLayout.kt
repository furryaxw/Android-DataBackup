package com.xayah.core.data.repository

import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.util.PathUtil

/**
 * Owns repository-first path mapping for local and cloud repository roots.
 *
 * CloudClient upload APIs expect a destination directory, not a full destination
 * file path. Keep that distinction here to avoid paths like config/config.
 */
class RepositoryLayout private constructor(
    private val root: String,
) {
    companion object {
        const val REPOSITORY_DIR = "repository"
        const val MANIFEST_FILE = "manifest.json"
        const val META_FILE = "meta.json"
        const val ITEM_MANIFEST_FILE = "databackup-item.json"

        fun fromRoot(root: String) = RepositoryLayout(root.trimEnd('/'))
    }

    val repositoryRoot: String = "$root/$REPOSITORY_DIR"
    val manifestPath: String = "$repositoryRoot/$MANIFEST_FILE"
    val metaPath: String = "$repositoryRoot/$META_FILE"

    fun appsRoot(): String = "$repositoryRoot/apps"

    fun filesRoot(): String = "$repositoryRoot/files"

    fun appRepositoryPath(packageName: String, userId: Int, preserveId: Long = 0L): String {
        return "${appsRoot()}/${appItemRelativeDir(packageName, userId, preserveId)}"
    }

    fun appRepositoryPath(packageEntity: PackageEntity): String {
        return appRepositoryPath(packageEntity.packageName, packageEntity.userId, packageEntity.preserveId)
    }

    fun fileRepositoryPath(name: String, preserveId: Long = 0L): String {
        return "${filesRoot()}/${fileItemRelativeDir(name, preserveId)}"
    }

    fun fileRepositoryPath(mediaEntity: MediaEntity): String {
        return fileRepositoryPath(mediaEntity.name, mediaEntity.preserveId)
    }

    fun itemManifestPath(repositoryPath: String): String = "$repositoryPath/$ITEM_MANIFEST_FILE"

    fun relativeRepositoryPath(absolutePath: String): String {
        val normalized = absolutePath.trimEnd('/')
        val prefix = "$repositoryRoot/"
        require(normalized == repositoryRoot || normalized.startsWith(prefix)) {
            "Path is outside repository root: $absolutePath"
        }
        return if (normalized == repositoryRoot) "" else normalized.removePrefix(prefix)
    }

    fun remoteParentDirForRelativeFile(relativePath: String): String {
        val normalized = relativePath.trim('/').replace('\\', '/')
        require(normalized.isNotEmpty()) { "Relative path must not be empty." }
        val parent = PathUtil.getParentPath(normalized)
        return if (parent.isEmpty()) repositoryRoot else "$repositoryRoot/$parent"
    }

    private fun appItemRelativeDir(packageName: String, userId: Int, preserveId: Long): String {
        return "${sanitizeSegment(packageName)}/user_${userId}${preserveSuffix(preserveId)}"
    }

    private fun fileItemRelativeDir(name: String, preserveId: Long): String {
        return "${sanitizeSegment(name)}${preserveSuffix(preserveId)}"
    }

    private fun preserveSuffix(preserveId: Long): String = if (preserveId == 0L) "" else "@$preserveId"

    private fun sanitizeSegment(value: String): String {
        return value.trim('/').replace('/', '_').replace('\\', '_')
    }
}
