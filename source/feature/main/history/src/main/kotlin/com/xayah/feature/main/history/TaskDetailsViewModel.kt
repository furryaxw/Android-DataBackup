package com.xayah.feature.main.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.ui.route.MainRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TaskDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepo: TaskRepository,
    private val backupRetryRepo: com.xayah.core.data.repository.BackupRetryRepository,
    private val mediaRepo: com.xayah.core.data.repository.MediaRepository,
) : ViewModel() {
    private val id: Long = savedStateHandle.get<String>(MainRoutes.ARG_ID)?.toLongOrNull() ?: 0L

    val uiState: StateFlow<TaskDetailsUiState> = combine(
        taskRepo.queryTaskFlow(id),
        taskRepo.queryProcessingInfoFlow(id),
        taskRepo.queryPackageFlow(id),
        taskRepo.queryMediaFlow(id)
    ) { task, processingInfoList, appInfoList, fileInfoList ->
        if (task == null) {
            TaskDetailsUiState.Error
        } else {
            TaskDetailsUiState.Success(
                task = task,
                preprocessingInfoList = processingInfoList.filter { it.type == ProcessingType.PREPROCESSING },
                postProcessingInfoList = processingInfoList.filter { it.type == ProcessingType.POST_PROCESSING },
                appInfoList = appInfoList,
                fileInfoList = fileInfoList
            )
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = TaskDetailsUiState.Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    suspend fun retryFailedApps(): Int {
        val state = uiState.value
        if (state !is TaskDetailsUiState.Success) return 0
        return backupRetryRepo.prepareFailedPackageRetry(state.appInfoList)
    }

    suspend fun retryFailedFiles(): Int {
        val state = uiState.value
        if (state !is TaskDetailsUiState.Success) return 0
        val files = state.fileInfoList
        return mediaRepo.reactivateFailedFromTask(files).size
    }
}

sealed interface TaskDetailsUiState {
    data object Loading : TaskDetailsUiState
    data class Success(
        val task: TaskEntity,
        val preprocessingInfoList: List<ProcessingInfoEntity>,
        val postProcessingInfoList: List<ProcessingInfoEntity>,
        val appInfoList: List<TaskDetailPackageEntity>,
        val fileInfoList: List<TaskDetailMediaEntity>,
    ) : TaskDetailsUiState

    data object Error : TaskDetailsUiState
}
