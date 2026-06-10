package com.xayah.core.data.repository

import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRetryRepository @Inject constructor(
    private val packageDao: PackageDao,
) {
    private val _retryPackageIds = MutableStateFlow<List<Long>>(emptyList())

    val retryPackageIds: StateFlow<List<Long>> = _retryPackageIds.asStateFlow()

    suspend fun prepareFailedPackageRetry(failedItems: List<TaskDetailPackageEntity>): Int {
        val ids = failedItems
            .filter { it.isSuccess.not() }
            .mapNotNull { item ->
                packageDao.query(
                    packageName = item.packageEntity.packageName,
                    opType = OpType.BACKUP,
                    userId = item.packageEntity.userId,
                )?.id
            }
            .distinct()
        _retryPackageIds.value = ids
        return ids.size
    }

    suspend fun queryRetryPackages(): List<PackageEntity> {
        val ids = _retryPackageIds.value
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { packageDao.queryById(it) }
    }

    fun hasPackageRetry(): Boolean = _retryPackageIds.value.isNotEmpty()

    fun clearPackageRetry() {
        _retryPackageIds.value = emptyList()
    }
}
