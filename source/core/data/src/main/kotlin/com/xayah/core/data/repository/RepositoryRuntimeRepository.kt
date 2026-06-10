package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.data.R
import com.xayah.core.datastore.di.ApplicationScope
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.datastore.readDefaultSyncCloud
import com.xayah.core.model.SyncDirection
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

enum class RepositoryRuntimeOperation {
    REFRESH,
    PUSH,
    PULL,
    CHECK,
    CLEAR,
}

data class RepositoryRuntimeState(
    val localRepository: String = "",
    val selectedCloudName: String = "",
    val selectedCloudRepository: String = "",
    val localStats: RepositorySyncStats = RepositorySyncStats(),
    val cloudStats: RepositorySyncStats = RepositorySyncStats(),
    val cloudStatsUpdatedAt: Long = 0,
    val isRefreshing: Boolean = false,
    val isProcessing: Boolean = false,
    val operation: RepositoryRuntimeOperation? = null,
    val currentPath: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val currentReadBytes: Long = 0,
    val currentTotalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val transferSpeedBytesPerSecond: Long = 0,
    val errors: List<String> = emptyList(),
    val lastFailedPull: Boolean? = null,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val message: String = "",
    val completedEvent: Long = 0,
)

data class RepositoryRuntimeProgress(
    val currentPath: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val currentReadBytes: Long = 0,
    val currentTotalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
)

@Singleton
class RepositoryRuntimeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepository: CloudRepository,
    private val backupEngineRepository: BackupEngineRepository,
    private val restoreCatalogRepository: RepositoryRestoreCatalogRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private enum class StopReason {
        PAUSE,
        CANCEL,
    }

    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(RepositoryRuntimeState())
    private val progressLock = Any()
    private var runningJob: Job? = null

    @Volatile
    private var runningSyncCancellationToken: RepositorySyncCancellationToken? = null
    private var latestProgress = RepositoryRuntimeProgress()

    @Volatile
    private var stopReason: StopReason? = null

    val state: StateFlow<RepositoryRuntimeState> = _state.asStateFlow()

    private fun log(msg: () -> String) {
        LogUtil.log { "RepositoryRuntimeRepository" to msg() }
    }

    fun currentSyncProgress(): RepositoryRuntimeProgress = synchronized(progressLock) { latestProgress }

    suspend fun loadCached(preferredCloudName: String? = null) {
        if (_state.value.isProcessing || _state.value.isRefreshing) return
        refreshMutex.withLock {
            loadCachedInternal(preferredCloudName = preferredCloudName)
        }
    }

    suspend fun refresh(
        preferredCloudName: String? = null,
        includeRemote: Boolean = true,
        reloadCatalog: Boolean = true,
    ) {
        if (_state.value.isProcessing) return
        refreshMutex.withLock {
            refreshInternal(
                preferredCloudName = preferredCloudName,
                publishOperation = true,
                reloadCatalog = reloadCatalog,
                includeRemote = includeRemote,
            )
        }
    }

    fun startPush(cloudName: String? = null): Boolean = startSync(pull = false, cloudName = cloudName)

    fun startPull(cloudName: String? = null): Boolean = startSync(pull = true, cloudName = cloudName)

    fun retryFailed(): Boolean {
        val pull = _state.value.lastFailedPull ?: return false
        return startSync(pull = pull, cloudName = _state.value.selectedCloudName.ifEmpty { null })
    }

    fun pause() {
        stopRunning(StopReason.PAUSE)
    }

    fun cancel() {
        stopRunning(StopReason.CANCEL)
    }

    fun startCheckRepositories(): Boolean = startMaintenance(RepositoryRuntimeOperation.CHECK)

    fun startClearRepositories(): Boolean = startMaintenance(RepositoryRuntimeOperation.CLEAR)

    private fun stopRunning(reason: StopReason) {
        stopReason = reason
        runningSyncCancellationToken?.cancel()
        runningJob?.cancel()
    }

    private fun startSync(pull: Boolean, cloudName: String?): Boolean {
        if (runningJob?.isActive == true || _state.value.isRefreshing) return false
        stopReason = null
        val cancellationToken = RepositorySyncCancellationToken()
        runningSyncCancellationToken = cancellationToken
        runningJob = applicationScope.launch {
            try {
                runSync(pull = pull, cloudName = cloudName, cancellationToken = cancellationToken)
            } finally {
                if (runningSyncCancellationToken === cancellationToken) {
                    runningSyncCancellationToken = null
                }
            }
        }
        return true
    }

    private fun startMaintenance(operation: RepositoryRuntimeOperation): Boolean {
        if (runningJob?.isActive == true || _state.value.isRefreshing) return false
        stopReason = null
        runningSyncCancellationToken = null
        runningJob = applicationScope.launch {
            runMaintenance(operation)
        }
        return true
    }

    private suspend fun runSync(
        pull: Boolean,
        cloudName: String?,
        cancellationToken: RepositorySyncCancellationToken,
    ) {
        val operation = if (pull) RepositoryRuntimeOperation.PULL else RepositoryRuntimeOperation.PUSH
        val backupDir = context.localBackupSaveDir()
        val localRepository = RepositoryLayout.fromRoot(backupDir).repositoryRoot
        val cloud = preferredCloud(cloudName)
        if (cloud == null) {
            val error = context.getString(R.string.no_available_cloud_accounts)
            log { "Repository sync $operation aborted: $error" }
            _state.update {
                it.copy(
                    localRepository = localRepository,
                    selectedCloudName = "",
                    selectedCloudRepository = "",
                    errors = listOf(error),
                    message = error,
                )
            }
            return
        }

        val remoteRepository = RepositoryLayout.fromRoot(cloud.remote).repositoryRoot
        resetSyncProgress()
        _state.update {
            it.copy(
                localRepository = localRepository,
                selectedCloudName = cloud.name,
                selectedCloudRepository = remoteRepository,
                isProcessing = true,
                operation = operation,
                currentPath = "",
                processedItems = 0,
                totalItems = 0,
                currentReadBytes = 0,
                currentTotalBytes = 0,
                transferredBytes = 0,
                totalBytes = 0,
                transferSpeedBytesPerSecond = 0,
                errors = emptyList(),
                lastFailedPull = null,
                isPaused = false,
                isCancelled = false,
                message = "",
            )
        }

        val result = try {
            if (pull) {
                cloudRepository.forcePullRepository(
                    cloud = cloud.name,
                    remoteRepositoryRoot = remoteRepository,
                    localRepositoryRoot = localRepository,
                    onProgress = ::updateSyncProgress,
                    cancellationToken = cancellationToken,
                )
            } else {
                cloudRepository.forcePushRepository(
                    cloud = cloud.name,
                    localRepositoryRoot = localRepository,
                    remoteRepositoryRoot = remoteRepository,
                    onProgress = ::updateSyncProgress,
                    cancellationToken = cancellationToken,
                )
            }
        } catch (_: CancellationException) {
            handleStoppedSync()
            return
        } catch (e: Throwable) {
            val error = e.describe()
            log { "Repository sync $operation threw: $error\n${e.stackTraceToString()}" }
            RepositorySyncResult(errors = listOf(error))
        }
        logSyncErrors("sync $operation", result.errors)

        if (result.isSuccess) {
            runCatching { cloudRepository.updateCachedRepositoryStats(cloud.name, remoteRepository, result.stats) }
            reloadLocalCatalog()
        }
        val progress = currentSyncProgress()
        val finalTotalItems = progress.totalItems.takeIf { it > 0 } ?: result.transferStats.fileCount
        val finalProcessedItems = if (result.isSuccess) finalTotalItems else progress.processedItems
        val finalTransferredBytes = if (result.isSuccess) result.transferStats.totalBytes else progress.transferredBytes
        val finalTotalBytes = maxOf(progress.totalBytes, result.transferStats.totalBytes)
        val localStats = if (result.isSuccess) {
            result.stats
        } else {
            runCatching { cloudRepository.localRepositoryStats(localRepository) }.getOrDefault(_state.value.localStats)
        }
        val cachedCloudStats = runCatching { cloudRepository.cachedRepositoryStats(cloud.name, remoteRepository) }.getOrNull()
        val cloudStats = if (result.isSuccess) {
            result.stats
        } else {
            cachedCloudStats?.stats ?: _state.value.cloudStats
        }
        _state.update {
            it.copy(
                localStats = localStats,
                cloudStats = cloudStats,
                cloudStatsUpdatedAt = if (result.isSuccess) DateUtil.getTimestamp() else cachedCloudStats?.updatedAt ?: it.cloudStatsUpdatedAt,
                isProcessing = false,
                operation = null,
                currentPath = progress.currentPath,
                processedItems = finalProcessedItems,
                totalItems = finalTotalItems,
                currentReadBytes = if (result.isSuccess) 0 else progress.currentReadBytes,
                currentTotalBytes = if (result.isSuccess) 0 else progress.currentTotalBytes,
                transferredBytes = finalTransferredBytes,
                totalBytes = finalTotalBytes,
                transferSpeedBytesPerSecond = 0,
                errors = result.errors,
                lastFailedPull = if (result.isSuccess) null else pull,
                isPaused = false,
                isCancelled = false,
                message = if (result.isSuccess) context.getString(R.string.succeed) else result.errors.take(3).joinToString("\n"),
                completedEvent = it.completedEvent + 1,
            )
        }
    }

    private suspend fun runMaintenance(operation: RepositoryRuntimeOperation) {
        _state.update {
            it.copy(
                isProcessing = true,
                operation = operation,
                currentPath = "",
                processedItems = 0,
                totalItems = 0,
                currentReadBytes = 0,
                currentTotalBytes = 0,
                transferredBytes = 0,
                totalBytes = 0,
                transferSpeedBytesPerSecond = 0,
                errors = emptyList(),
                message = "",
            )
        }

        val errors = mutableListOf<String>()
        when (operation) {
            RepositoryRuntimeOperation.CHECK -> {
                runCatching {
                    backupEngineRepository.checkAllRepositories(::updateMaintenanceProgress)
                }.onSuccess { results ->
                    errors.addAll(results.filterNot { it.isSuccess }.map { it.toString() })
                }.onFailure {
                    val error = it.describe()
                    log { "Repository maintenance $operation failed: $error\n${it.stackTraceToString()}" }
                    errors.add(error)
                }
            }

            RepositoryRuntimeOperation.CLEAR -> {
                runCatching {
                    backupEngineRepository.clearAllRepositories(::updateMaintenanceProgress)
                }.onSuccess { deleted ->
                    if (deleted.not()) errors.add(context.getString(R.string.failed))
                }.onFailure {
                    val error = it.describe()
                    log { "Repository maintenance $operation failed: $error\n${it.stackTraceToString()}" }
                    errors.add(error)
                }
            }

            else -> {}
        }
        logSyncErrors("maintenance $operation", errors)

        refreshInternal(
            preferredCloudName = _state.value.selectedCloudName.ifEmpty { null },
            publishOperation = false,
            reloadCatalog = operation == RepositoryRuntimeOperation.CHECK,
            includeRemote = false,
        )
        _state.update {
            it.copy(
                isProcessing = false,
                operation = null,
                errors = errors,
                message = if (errors.isEmpty()) context.getString(R.string.succeed) else errors.take(3).joinToString("\n"),
                completedEvent = it.completedEvent + 1,
            )
        }
    }

    private suspend fun refreshInternal(
        preferredCloudName: String?,
        publishOperation: Boolean,
        reloadCatalog: Boolean,
        includeRemote: Boolean,
    ) {
        val backupDir = context.localBackupSaveDir()
        val localRepository = RepositoryLayout.fromRoot(backupDir).repositoryRoot
        val cloud = preferredCloud(preferredCloudName)
        val remoteRepository = cloud?.remote?.let { RepositoryLayout.fromRoot(it).repositoryRoot }.orEmpty()
        val errors = mutableListOf<String>()

        if (publishOperation) {
            _state.update {
                it.copy(
                    localRepository = localRepository,
                    selectedCloudName = cloud?.name.orEmpty(),
                    selectedCloudRepository = remoteRepository,
                    isRefreshing = true,
                    operation = RepositoryRuntimeOperation.REFRESH,
                    currentPath = "",
                    processedItems = 0,
                    totalItems = 0,
                    currentReadBytes = 0,
                    currentTotalBytes = 0,
                    transferredBytes = 0,
                    totalBytes = 0,
                    transferSpeedBytesPerSecond = 0,
                    errors = emptyList(),
                    message = "",
                )
            }
        } else {
            _state.update {
                it.copy(
                    localRepository = localRepository,
                    selectedCloudName = cloud?.name.orEmpty(),
                    selectedCloudRepository = remoteRepository,
                )
            }
        }

        val localStats = runCatching { cloudRepository.localRepositoryStats(localRepository) }
            .onFailure {
                val error = it.describe()
                log { "Repository refresh local stats failed: $error\n${it.stackTraceToString()}" }
                errors.add(error)
            }
            .getOrDefault(RepositorySyncStats())

        if (reloadCatalog) {
            runCatching { reloadLocalCatalog() }
                .onFailure {
                    val error = it.describe()
                    log { "Repository refresh reload catalog failed: $error\n${it.stackTraceToString()}" }
                    errors.add(error)
                }
        }

        val cachedCloudStats = if (cloud == null) {
            null
        } else {
            runCatching { cloudRepository.cachedRepositoryStats(cloud.name, remoteRepository) }.getOrNull()
        }

        val scannedCloudStats = if (cloud == null || includeRemote.not()) {
            null
        } else {
            runCatching { cloudRepository.remoteRepositoryStats(cloud.name, remoteRepository) }
                .onFailure {
                    val error = it.describe()
                    log { "Repository refresh remote stats failed: $error\n${it.stackTraceToString()}" }
                    errors.add(error)
                }
                .getOrNull()
        }
        val refreshedCachedCloudStats = if (cloud == null || scannedCloudStats == null) {
            null
        } else {
            runCatching { cloudRepository.cachedRepositoryStats(cloud.name, remoteRepository) }.getOrNull()
        }
        val effectiveCachedCloudStats = refreshedCachedCloudStats ?: cachedCloudStats
        val cloudStats = scannedCloudStats ?: effectiveCachedCloudStats?.stats ?: RepositorySyncStats()
        val cloudStatsUpdatedAt = when {
            scannedCloudStats != null -> effectiveCachedCloudStats?.updatedAt ?: DateUtil.getTimestamp()
            effectiveCachedCloudStats != null -> effectiveCachedCloudStats.updatedAt
            else -> 0L
        }

        _state.update {
            it.copy(
                localStats = localStats,
                cloudStats = cloudStats,
                cloudStatsUpdatedAt = cloudStatsUpdatedAt,
                isRefreshing = if (publishOperation) false else it.isRefreshing,
                operation = if (publishOperation) null else it.operation,
                errors = if (errors.isEmpty()) it.errors else errors,
                message = if (errors.isEmpty()) it.message else errors.take(3).joinToString("\n"),
                completedEvent = if (publishOperation) it.completedEvent + 1 else it.completedEvent,
            )
        }
        logSyncErrors("refresh", errors)
    }

    private suspend fun reloadLocalCatalog() {
        restoreCatalogRepository.reloadLocal { message ->
            _state.update { it.copy(currentPath = message, message = message) }
        }
    }

    private suspend fun loadCachedInternal(preferredCloudName: String?) {
        val backupDir = context.localBackupSaveDir()
        val localRepository = RepositoryLayout.fromRoot(backupDir).repositoryRoot
        val cloud = preferredCloud(preferredCloudName)
        val remoteRepository = cloud?.remote?.let { RepositoryLayout.fromRoot(it).repositoryRoot }.orEmpty()
        val localStats = runCatching { cloudRepository.localRepositoryStats(localRepository) }.getOrDefault(_state.value.localStats)
        val cachedCloudStats = if (cloud == null) {
            null
        } else {
            runCatching { cloudRepository.cachedRepositoryStats(cloud.name, remoteRepository) }.getOrNull()
        }
        _state.update {
            it.copy(
                localRepository = localRepository,
                selectedCloudName = cloud?.name.orEmpty(),
                selectedCloudRepository = remoteRepository,
                localStats = localStats,
                cloudStats = cachedCloudStats?.stats ?: RepositorySyncStats(),
                cloudStatsUpdatedAt = cachedCloudStats?.updatedAt ?: 0L,
            )
        }
    }

    private fun resetSyncProgress() {
        synchronized(progressLock) {
            latestProgress = RepositoryRuntimeProgress()
        }
    }

    private fun updateSyncProgress(progress: RepositorySyncProgress) {
        synchronized(progressLock) {
            latestProgress = RepositoryRuntimeProgress(
                currentPath = progress.relativePath,
                processedItems = progress.processedItems,
                totalItems = progress.totalItems,
                currentReadBytes = progress.currentReadBytes,
                currentTotalBytes = progress.currentTotalBytes,
                transferredBytes = progress.transferredBytes,
                totalBytes = progress.totalBytes,
            )
        }
    }

    private fun updateMaintenanceProgress(progress: RepositoryMaintenanceProgress) {
        _state.update {
            it.copy(
                currentPath = progress.currentPath,
                processedItems = progress.processedItems,
                totalItems = progress.totalItems,
            )
        }
    }

    private fun handleStoppedSync() {
        val paused = stopReason == StopReason.PAUSE
        val progress = currentSyncProgress()
        _state.update {
            it.copy(
                isProcessing = false,
                operation = null,
                currentPath = progress.currentPath,
                processedItems = progress.processedItems,
                totalItems = progress.totalItems,
                currentReadBytes = progress.currentReadBytes,
                currentTotalBytes = progress.currentTotalBytes,
                transferredBytes = progress.transferredBytes,
                totalBytes = progress.totalBytes,
                transferSpeedBytesPerSecond = 0,
                isPaused = paused,
                isCancelled = paused.not(),
                message = context.getString(if (paused) R.string.sync_paused else R.string.sync_cancelled),
                completedEvent = it.completedEvent + 1,
            )
        }
    }

    private fun logSyncErrors(scope: String, errors: List<String>) {
        errors.forEachIndexed { index, error ->
            log { "Repository $scope error ${index + 1}/${errors.size}: $error" }
        }
    }

    private suspend fun preferredCloud(name: String?): CloudEntity? {
        val clouds = cloudRepository.query()
        if (clouds.isEmpty()) return null
        val requestedName = name.orEmpty()
        val currentName = _state.value.selectedCloudName
        val defaultName = context.readDefaultSyncCloud().first()
        val activeName = context.readCloudActivatedAccountName().first()
        return clouds.firstOrNull { it.name == requestedName }
            ?: clouds.firstOrNull { it.name == currentName }
            ?: clouds.firstOrNull { it.name == defaultName }
            ?: clouds.firstOrNull { it.name == activeName }
            ?: clouds.first()
    }

    private fun Throwable.describe(): String = localizedMessage ?: message ?: toString()
}
