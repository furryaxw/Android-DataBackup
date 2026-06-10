package com.xayah.feature.main.restore.legacy

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.LegacyBackupRepository
import com.xayah.core.data.repository.LegacyBackupSummary
import com.xayah.core.model.OpType
import com.xayah.core.model.RestoreSource
import com.xayah.core.model.Target
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.encodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.restore.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class LegacyUiState(
    val summary: LegacyBackupSummary = LegacyBackupSummary(),
    val isLoading: Boolean = false,
    val text: String = "",
) : UiState

sealed class LegacyUiIntent : UiIntent {
    data object Refresh : LegacyUiIntent()
    data object Reload : LegacyUiIntent()
    data object ImportToRepository : LegacyUiIntent()
    data class ToAppList(val navController: NavHostController) : LegacyUiIntent()
    data class ToFileList(val navController: NavHostController) : LegacyUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class LegacyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val legacyBackupRepository: LegacyBackupRepository,
) : BaseViewModel<LegacyUiState, LegacyUiIntent, IndexUiEffect>(
    LegacyUiState(text = context.getString(R.string.idle))
) {
    override suspend fun onEvent(state: LegacyUiState, intent: LegacyUiIntent) {
        when (intent) {
            LegacyUiIntent.Refresh -> {
                emitState(state.copy(summary = legacyBackupRepository.detectLocal()))
            }

            LegacyUiIntent.Reload -> {
                emitState(state.copy(isLoading = true, text = context.getString(R.string.loading_backups)))
                val result = legacyBackupRepository.reloadLocal {
                    emitState(uiState.value.copy(text = it))
                }
                emitState(
                    uiState.value.copy(
                        isLoading = false,
                        summary = legacyBackupRepository.detectLocal(),
                        text = context.getString(R.string.args_legacy_reloaded, result.appsCount, result.filesCount),
                    )
                )
            }

            LegacyUiIntent.ImportToRepository -> {
                emitState(state.copy(isLoading = true, text = context.getString(R.string.processing)))
                val result = legacyBackupRepository.importLocalToRepository {
                    emitState(uiState.value.copy(text = it))
                }
                val text = if (result.isSuccess) {
                    context.getString(R.string.args_legacy_imported, result.appSnapshots, result.fileSnapshots)
                } else {
                    result.errors.take(3).joinToString("\n")
                }
                emitState(
                    uiState.value.copy(
                        isLoading = false,
                        summary = legacyBackupRepository.detectLocal(),
                        text = text,
                    )
                )
            }

            is LegacyUiIntent.ToAppList -> {
                emitState(state.copy(isLoading = true, text = context.getString(R.string.loading_backups)))
                legacyBackupRepository.reloadLocal {
                    emitState(uiState.value.copy(text = it))
                }
                emitState(uiState.value.copy(isLoading = false, summary = legacyBackupRepository.detectLocal()))
                withMainContext {
                    intent.navController.navigateSingle(
                        MainRoutes.List.getRoute(
                            target = Target.Apps,
                            opType = OpType.RESTORE,
                            backupDir = context.localBackupSaveDir().encodeURL(),
                            restoreSource = RestoreSource.LEGACY,
                        )
                    )
                }
            }

            is LegacyUiIntent.ToFileList -> {
                emitState(state.copy(isLoading = true, text = context.getString(R.string.loading_backups)))
                legacyBackupRepository.reloadLocal {
                    emitState(uiState.value.copy(text = it))
                }
                emitState(uiState.value.copy(isLoading = false, summary = legacyBackupRepository.detectLocal()))
                withMainContext {
                    intent.navController.navigateSingle(
                        MainRoutes.List.getRoute(
                            target = Target.Files,
                            opType = OpType.RESTORE,
                            backupDir = context.localBackupSaveDir().encodeURL(),
                            restoreSource = RestoreSource.LEGACY,
                        )
                    )
                }
            }
        }
    }
}
