package com.xayah.feature.main.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.RepositoryRuntimeOperation
import com.xayah.core.data.repository.RepositoryRuntimeRepository
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class IndexUiState(
    val isProcessing: Boolean = false,
    val operation: RepositoryRuntimeOperation? = null,
    val currentPath: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val message: String = "",
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object CheckRepositories : IndexUiIntent()
    data object ClearRepositories : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    directoryRepo: DirectoryRepository,
    private val repositoryRuntimeRepo: RepositoryRuntimeRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState()) {
    init {
        launchOnIO {
            var lastCompletedEvent = repositoryRuntimeRepo.state.value.completedEvent
            repositoryRuntimeRepo.state.collect { runtime ->
                emitState(
                    uiState.value.copy(
                        isProcessing = runtime.isProcessing,
                        operation = runtime.operation,
                        currentPath = runtime.currentPath,
                        processedItems = runtime.processedItems,
                        totalItems = runtime.totalItems,
                        message = runtime.message,
                    )
                )
                if (runtime.completedEvent != 0L && runtime.completedEvent != lastCompletedEvent && runtime.message.isNotBlank()) {
                    lastCompletedEvent = runtime.completedEvent
                    emitEffect(
                        IndexUiEffect.ShowSnackbar(
                            message = runtime.message,
                            type = if (runtime.errors.isEmpty()) SnackbarType.Success else SnackbarType.Error,
                            duration = if (runtime.errors.isEmpty()) SnackbarDuration.Short else SnackbarDuration.Long,
                        )
                    )
                }
            }
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            IndexUiIntent.CheckRepositories -> repositoryRuntimeRepo.startCheckRepositories()
            IndexUiIntent.ClearRepositories -> repositoryRuntimeRepo.startClearRepositories()
        }
    }

    private val _directory: Flow<DirectoryEntity?> = directoryRepo.querySelectedByDirectoryTypeFlow().flowOnIO()
    val directoryState: StateFlow<DirectoryEntity?> = _directory.stateInScope(null)
}
