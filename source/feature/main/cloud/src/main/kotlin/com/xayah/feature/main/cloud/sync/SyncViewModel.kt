package com.xayah.feature.main.cloud.sync

import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.RepositoryRuntimeOperation
import com.xayah.core.data.repository.RepositoryRuntimeRepository
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.feature.main.cloud.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class SyncUiState(
    val localRepository: String = "",
    val selectedCloudName: String = "",
    val selectedCloudRepository: String = "",
    val localFileCount: Int = 0,
    val localBytes: Long = 0,
    val cloudFileCount: Int = 0,
    val cloudBytes: Long = 0,
    val cloudStatsUpdatedAt: Long = 0,
    val isProcessing: Boolean = false,
    val currentPath: String = "",
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val currentReadBytes: Long = 0,
    val currentTotalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val transferSpeedBytesPerSecond: Long = 0,
    val errors: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val operation: SyncOperation? = null,
) : UiState

enum class SyncOperation {
    PUSH,
    PULL,
}

sealed class SyncUiIntent : UiIntent {
    data object LoadCached : SyncUiIntent()
    data object Refresh : SyncUiIntent()
    data object ForcePush : SyncUiIntent()
    data object ForcePull : SyncUiIntent()
    data object Pause : SyncUiIntent()
    data object Cancel : SyncUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val cloudRepo: CloudRepository,
    private val repositoryRuntimeRepo: RepositoryRuntimeRepository,
) : BaseViewModel<SyncUiState, SyncUiIntent, IndexUiEffect>(SyncUiState()) {
    init {
        launchOnIO {
            var lastCompletedEvent = repositoryRuntimeRepo.state.value.completedEvent
            repositoryRuntimeRepo.state.collect { runtime ->
                emitState(
                    uiState.value.copy(
                        localRepository = runtime.localRepository,
                        selectedCloudName = runtime.selectedCloudName,
                        selectedCloudRepository = runtime.selectedCloudRepository,
                        localFileCount = runtime.localStats.fileCount,
                        localBytes = runtime.localStats.totalBytes,
                        cloudFileCount = runtime.cloudStats.fileCount,
                        cloudBytes = runtime.cloudStats.totalBytes,
                        cloudStatsUpdatedAt = runtime.cloudStatsUpdatedAt,
                        isProcessing = runtime.isProcessing,
                        currentPath = if (runtime.isProcessing) uiState.value.currentPath else runtime.currentPath,
                        processedFiles = if (runtime.isProcessing) uiState.value.processedFiles else runtime.processedItems,
                        totalFiles = if (runtime.isProcessing) uiState.value.totalFiles else runtime.totalItems,
                        currentReadBytes = if (runtime.isProcessing) uiState.value.currentReadBytes else runtime.currentReadBytes,
                        currentTotalBytes = if (runtime.isProcessing) uiState.value.currentTotalBytes else runtime.currentTotalBytes,
                        transferredBytes = if (runtime.isProcessing) uiState.value.transferredBytes else runtime.transferredBytes,
                        totalBytes = if (runtime.isProcessing) uiState.value.totalBytes else runtime.totalBytes,
                        transferSpeedBytesPerSecond = if (runtime.isProcessing) uiState.value.transferSpeedBytesPerSecond else runtime.transferSpeedBytesPerSecond,
                        errors = runtime.errors,
                        isRefreshing = runtime.isRefreshing,
                        message = runtime.message,
                        isPaused = runtime.isPaused,
                        isCancelled = runtime.isCancelled,
                        operation = when (runtime.operation) {
                            RepositoryRuntimeOperation.PUSH -> SyncOperation.PUSH
                            RepositoryRuntimeOperation.PULL -> SyncOperation.PULL
                            else -> null
                        },
                    )
                )
                if (runtime.completedEvent != 0L && runtime.completedEvent != lastCompletedEvent && runtime.message.isNotBlank()) {
                    lastCompletedEvent = runtime.completedEvent
                    emitEffect(
                        IndexUiEffect.ShowSnackbar(
                            message = runtime.message,
                            type = when {
                                runtime.errors.isNotEmpty() -> SnackbarType.Error
                                runtime.isPaused || runtime.isCancelled -> SnackbarType.Warning
                                else -> SnackbarType.Success
                            },
                            duration = if (runtime.errors.isNotEmpty()) SnackbarDuration.Long else SnackbarDuration.Short,
                        )
                    )
                }
            }
        }
        launchOnIO {
            var previousBytes = 0L
            var previousTime = System.currentTimeMillis()
            var wasProcessing = false
            while (true) {
                delay(500)
                val runtime = repositoryRuntimeRepo.state.value
                if (runtime.isProcessing && (runtime.operation == RepositoryRuntimeOperation.PUSH || runtime.operation == RepositoryRuntimeOperation.PULL)) {
                    val progress = repositoryRuntimeRepo.currentSyncProgress()
                    val now = System.currentTimeMillis()
                    val speed = if (wasProcessing) {
                        val bytesDelta = (progress.transferredBytes - previousBytes).coerceAtLeast(0)
                        val timeDelta = (now - previousTime).coerceAtLeast(1)
                        bytesDelta * 1000L / timeDelta
                    } else {
                        0L
                    }
                    previousBytes = progress.transferredBytes
                    previousTime = now
                    wasProcessing = true
                    emitState(
                        uiState.value.copy(
                            currentPath = progress.currentPath,
                            processedFiles = progress.processedItems,
                            totalFiles = progress.totalItems,
                            currentReadBytes = progress.currentReadBytes,
                            currentTotalBytes = progress.currentTotalBytes,
                            transferredBytes = progress.transferredBytes,
                            totalBytes = progress.totalBytes,
                            transferSpeedBytesPerSecond = speed,
                        )
                    )
                } else {
                    previousBytes = 0L
                    previousTime = System.currentTimeMillis()
                    wasProcessing = false
                }
            }
        }
    }

    override suspend fun onEvent(state: SyncUiState, intent: SyncUiIntent) {
        when (intent) {
            SyncUiIntent.LoadCached -> {
                repositoryRuntimeRepo.loadCached()
            }

            SyncUiIntent.Refresh -> {
                repositoryRuntimeRepo.refresh(includeRemote = true, reloadCatalog = false)
            }

            SyncUiIntent.ForcePush -> {
                if (repositoryRuntimeRepo.startPush().not()) {
                    emitBusySnackbar()
                }
            }

            SyncUiIntent.ForcePull -> {
                if (repositoryRuntimeRepo.startPull().not()) {
                    emitBusySnackbar()
                }
            }

            SyncUiIntent.Pause -> {
                repositoryRuntimeRepo.pause()
            }

            SyncUiIntent.Cancel -> {
                repositoryRuntimeRepo.cancel()
            }
        }
    }

    val accounts: StateFlow<List<CloudEntity>> = cloudRepo.clouds.flowOnIO().stateInScope(emptyList())

    private suspend fun emitBusySnackbar() {
        emitEffect(
            IndexUiEffect.ShowSnackbar(
                message = cloudRepo.getString(R.string.processing),
                type = SnackbarType.Loading,
                duration = SnackbarDuration.Short,
            )
        )
    }

    fun bytesText(bytes: Long): String = bytes.toDouble().formatSize()

    fun speedText(bytesPerSecond: Long): String = "${bytesText(bytesPerSecond)}/s"
}
