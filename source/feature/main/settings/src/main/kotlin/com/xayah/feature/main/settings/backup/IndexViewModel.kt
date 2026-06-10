package com.xayah.feature.main.settings.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.BackupEngineRepository
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.RepositoryMaintenanceProgress
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.util.LogUtil
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.feature.main.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

enum class RepositoryMaintenanceOperation {
    CHECK,
    CLEAR,
}

data class BackupSettingsUiState(
    val isProcessing: Boolean = false,
    val operation: RepositoryMaintenanceOperation? = null,
    val currentPath: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
) : UiState

sealed class BackupSettingsUiIntent : UiIntent {
    data object CheckRepositories : BackupSettingsUiIntent()
    data object ClearRepositories : BackupSettingsUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupEngineRepo: BackupEngineRepository,
    cloudRepo: CloudRepository,
) : BaseViewModel<BackupSettingsUiState, BackupSettingsUiIntent, IndexUiEffect>(BackupSettingsUiState()) {
    private fun log(message: () -> String) {
        LogUtil.log { "BackupSettingsViewModel" to message() }
    }

    val accounts: StateFlow<List<CloudEntity>> = cloudRepo.clouds.flowOnIO().stateInScope(emptyList())

    override suspend fun onEvent(state: BackupSettingsUiState, intent: BackupSettingsUiIntent) {
        when (intent) {
            BackupSettingsUiIntent.CheckRepositories -> {
                log { "CheckRepositories clicked." }
                startProcessing(RepositoryMaintenanceOperation.CHECK)
                var failureMessage: String? = null
                val results = runCatching { backupEngineRepo.checkAllRepositories(::updateProgress) }
                    .onFailure {
                        failureMessage = it.localizedMessage ?: it.toString()
                        log { "CheckRepositories failed: ${it.stackTraceToString()}" }
                    }
                    .getOrElse { listOf() }
                stopProcessing()
                emitEffect(IndexUiEffect.DismissSnackbar)
                val message = when {
                    failureMessage != null -> failureMessage!!
                    results.isEmpty() -> context.getString(R.string.no_repositories_found)
                    else -> results.joinToString("\n") { if (it.isSuccess) "OK" else it.toString() }
                }
                emitEffect(
                    IndexUiEffect.ShowSnackbar(
                        message = message,
                        type = if (failureMessage == null && results.all { it.isSuccess }) SnackbarType.Success else SnackbarType.Error,
                    )
                )
            }

            BackupSettingsUiIntent.ClearRepositories -> {
                log { "ClearRepositories clicked." }
                startProcessing(RepositoryMaintenanceOperation.CLEAR)
                val deleted = runCatching { backupEngineRepo.clearAllRepositories(::updateProgress) }
                    .onFailure { log { "ClearRepositories failed: ${it.stackTraceToString()}" } }
                    .getOrDefault(false)
                stopProcessing()
                emitEffect(IndexUiEffect.DismissSnackbar)
                emitEffect(
                    IndexUiEffect.ShowSnackbar(
                        message = if (deleted) context.getString(R.string.succeed) else context.getString(R.string.clear_repository_failed),
                        type = if (deleted) SnackbarType.Success else SnackbarType.Error,
                    )
                )
            }
        }
    }

    private suspend fun startProcessing(operation: RepositoryMaintenanceOperation) {
        emitState(
            uiState.value.copy(
                isProcessing = true,
                operation = operation,
                currentPath = "",
                processedItems = 0,
                totalItems = 0,
            )
        )
        emitEffect(
            IndexUiEffect.ShowSnackbar(
                message = context.getString(R.string.processing),
                type = SnackbarType.Loading,
                duration = SnackbarDuration.Indefinite,
            )
        )
    }

    private suspend fun updateProgress(progress: RepositoryMaintenanceProgress) {
        log {
            "Repository maintenance progress: ${progress.processedItems}/${progress.totalItems} ${progress.currentPath}"
        }
        emitState(
            uiState.value.copy(
                currentPath = progress.currentPath,
                processedItems = progress.processedItems,
                totalItems = progress.totalItems,
            )
        )
    }

    private suspend fun stopProcessing() {
        emitState(
            uiState.value.copy(
                isProcessing = false,
                operation = null,
            )
        )
    }
}
