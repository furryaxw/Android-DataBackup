package com.xayah.feature.main.restore

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.RepositoryRuntimeRepository
import com.xayah.core.datastore.readLastRestoreTime
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.encodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class IndexUiState(
    val packages: List<PackageEntity>,
    val packagesSize: String,
    val medium: List<MediaEntity>,
    val mediumSize: String,
    val isRefreshing: Boolean = false,
    val refreshMessage: String = "",
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object UpdateApps : IndexUiIntent()
    data object UpdateFiles : IndexUiIntent()
    data object Refresh : IndexUiIntent()
    data class ToAppList(val navController: NavHostController) : IndexUiIntent()
    data class ToFileList(val navController: NavHostController) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pkgRepo: PackageRepository,
    private val mediaRepo: MediaRepository,
    private val repositoryRuntimeRepo: RepositoryRuntimeRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        packages = listOf(),
        packagesSize = "",
        medium = listOf(),
        mediumSize = "",
    )
) {
    init {
        val backupDir = context.localBackupSaveDir()
        launchOnIO {
            combine(
                pkgRepo.queryPackagesFlow(OpType.RESTORE, "", backupDir, repositorySource = true),
                mediaRepo.queryFilesFlow(OpType.RESTORE, "", backupDir, repositorySource = true),
                repositoryRuntimeRepo.state,
            ) { packages, medium, runtime ->
                IndexUiState(
                    packages = packages,
                    packagesSize = packages.sumOf { it.displayStatsBytes }.formatSize(),
                    medium = medium,
                    mediumSize = medium.sumOf { it.displayStatsBytes }.formatSize(),
                    isRefreshing = runtime.isRefreshing,
                    refreshMessage = runtime.message.ifEmpty { runtime.currentPath },
                )
            }.collect(::emitState)
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.UpdateApps -> {
                val packages = pkgRepo.queryPackages(OpType.RESTORE, "", context.localBackupSaveDir(), repositorySource = true)
                var bytes = 0.0
                packages.forEach { bytes += it.displayStatsBytes }
                emitState(state.copy(packages = packages, packagesSize = bytes.formatSize()))
            }

            is IndexUiIntent.UpdateFiles -> {
                val medium = mediaRepo.query(OpType.RESTORE, "", context.localBackupSaveDir(), repositorySource = true)
                var bytes = 0.0
                medium.forEach { bytes += it.displayStatsBytes }
                emitState(state.copy(medium = medium, mediumSize = bytes.formatSize()))
            }

            IndexUiIntent.Refresh -> {
                repositoryRuntimeRepo.refresh(includeRemote = false, reloadCatalog = true)
            }

            is IndexUiIntent.ToAppList -> {
                withMainContext {
                    intent.navController.navigateSingle(
                        MainRoutes.List.getRoute(
                            target = Target.Apps,
                            opType = OpType.RESTORE,
                            backupDir = context.localBackupSaveDir().encodeURL()
                        )
                    )
                }
            }

            is IndexUiIntent.ToFileList -> {
                withMainContext {
                    intent.navController.navigateSingle(
                        MainRoutes.List.getRoute(
                            target = Target.Files,
                            opType = OpType.RESTORE,
                            backupDir = context.localBackupSaveDir().encodeURL()
                        )
                    )
                }
            }

        }
    }

    private val _lastRestoreTime: Flow<Long> = context.readLastRestoreTime().flowOnIO()
    val lastRestoreTimeState: StateFlow<Long> = _lastRestoreTime.stateInScope(0)
}
