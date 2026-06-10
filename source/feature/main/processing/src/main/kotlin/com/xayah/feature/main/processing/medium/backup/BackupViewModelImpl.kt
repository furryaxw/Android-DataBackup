package com.xayah.feature.main.processing.medium.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.RepositoryRuntimeRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.datastore.readCloudSyncStrategy
import com.xayah.core.datastore.readDefaultSyncCloud
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.model.CloudSyncStrategy
import com.xayah.core.model.OpType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.medium.backup.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.component.DialogState
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractMediumProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class BackupViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mMediaRepo: MediaRepository,
    private val mCloudRepo: CloudRepository,
    private val mRepositoryRuntimeRepo: RepositoryRuntimeRepository,
    mLocalService: ProcessingServiceProxyLocalImpl,
) : AbstractMediumProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mLocalService) {
    override val supportsPostBackupSync: Boolean = true

    override suspend fun onProcessingDone(dialogState: DialogState, navController: NavHostController) {
        maybeAutoPushRepository(navController)
    }

    override suspend fun onPostBackupSyncClick(navController: NavHostController) {
        pushRepository(navController)
    }

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateFiles -> {
                val medium = mMediaRepo.queryActivated(OpType.BACKUP)
                var bytes = 0.0
                medium.forEach {
                    bytes += it.displayStatsBytes
                }
                _medium.value = medium
                _mediumSize.value = bytes.formatSize()
            }

            is SetCloudEntity -> {
                mContext.saveCloudActivatedAccountName(intent.name)
                emitState(state.copy(cloudEntity = mCloudRepo.queryByName(intent.name)))
            }

            is FinishSetup -> {
                withMainContext {
                    intent.navController.popBackStack()
                    intent.navController.navigateSingle(MainRoutes.MediumBackupProcessing.route)
                }
            }

            else -> {

            }
        }
    }

    private val _accounts: Flow<List<DialogRadioItem<Any>>> = mCloudRepo.clouds.map { entities ->
        entities.map {
            DialogRadioItem(
                enum = Any(),
                title = it.name,
                desc = it.user,
            )
        }
    }.flowOnIO()
    private val _isTesting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val _medium: MutableStateFlow<List<MediaEntity>> = MutableStateFlow(listOf())
    private val _mediumSize: MutableStateFlow<String> = MutableStateFlow("")

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val medium: StateFlow<List<MediaEntity>> = _medium.stateInScope(listOf())
    val mediumSize: StateFlow<String> = _mediumSize.stateInScope("")

    private suspend fun maybeAutoPushRepository(navController: NavHostController) {
        val shouldPush = when (mContext.readCloudSyncStrategy().first()) {
            CloudSyncStrategy.NEVER -> false
            CloudSyncStrategy.ON_SUCCESS -> task.value?.failureCount == 0
            CloudSyncStrategy.ALWAYS -> true
        }
        if (shouldPush) {
            pushRepository(navController)
        }
    }

    private suspend fun preferredSyncCloud(): CloudEntity? {
        val clouds = mCloudRepo.query()
        if (clouds.isEmpty()) return null
        val selected = uiState.value.cloudEntity
        val defaultName = mContext.readDefaultSyncCloud().first()
        val activeName = mContext.readCloudActivatedAccountName().first()
        return selected
            ?: clouds.firstOrNull { it.name == defaultName }
            ?: clouds.firstOrNull { it.name == activeName }
            ?: clouds.first()
    }

    private suspend fun pushRepository(navController: NavHostController) {
        val cloud = preferredSyncCloud()
        mRepositoryRuntimeRepo.startPush(cloud?.name)
        withMainContext {
            navController.navigate(MainRoutes.Sync.route) {
                popUpTo(MainRoutes.Dashboard.route) {
                    inclusive = false
                }
                launchSingleTop = true
            }
        }
    }
}
