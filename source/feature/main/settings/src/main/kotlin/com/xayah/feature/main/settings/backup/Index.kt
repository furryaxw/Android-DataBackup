package com.xayah.feature.main.settings.backup

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.KeyBackupConfigs
import com.xayah.core.datastore.KeyBackupItself
import com.xayah.core.datastore.KeyCheckKeystore
import com.xayah.core.datastore.KeyFollowSymlinks
import com.xayah.core.datastore.readCloudSyncStrategy
import com.xayah.core.datastore.readCloudUploadRetries
import com.xayah.core.datastore.readDefaultSyncCloud
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readSyncConcurrency
import com.xayah.core.datastore.saveCloudSyncStrategy
import com.xayah.core.datastore.saveDefaultSyncCloud
import com.xayah.core.datastore.saveCloudUploadRetries
import com.xayah.core.datastore.saveKillAppOption
import com.xayah.core.datastore.saveSyncConcurrency
import com.xayah.core.model.CloudSyncStrategy
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.util.indexOf
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Slideable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.select
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@SuppressLint("StringFormatInvalid")
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageBackupSettings() {
    val viewModel = hiltViewModel<BackupSettingsViewModel>()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.backup_settings),
        actions = {}
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Column {
                val scope = rememberCoroutineScope()
                val cloudSyncItems = stringArrayResource(id = R.array.cloud_sync_strategy_options)
                val cloudSyncDialogItems by remember(cloudSyncItems) {
                    mutableStateOf(cloudSyncItems.mapIndexed { index, s ->
                        DialogRadioItem(enum = CloudSyncStrategy.indexOf(index), title = s, desc = null)
                    })
                }
                val cloudSyncStrategy by context.readCloudSyncStrategy().collectAsStateWithLifecycle(initialValue = CloudSyncStrategy.NEVER)
                val cloudSyncStrategyIndex by remember(cloudSyncStrategy) { mutableIntStateOf(cloudSyncStrategy.ordinal) }
                Selectable(
                    title = stringResource(id = R.string.cloud_sync_strategy),
                    value = stringResource(id = R.string.cloud_sync_strategy_desc),
                    current = cloudSyncItems[cloudSyncStrategyIndex]
                ) {
                    val (state, selectedIndex) = dialogState.select(
                        title = context.getString(R.string.cloud_sync_strategy),
                        defIndex = cloudSyncStrategyIndex,
                        items = cloudSyncDialogItems
                    )
                    if (state.isConfirm) {
                        val selected = cloudSyncDialogItems[selectedIndex].enum!!
                        context.saveCloudSyncStrategy(selected)
                    }
                }

                val defaultCloudName by context.readDefaultSyncCloud().collectAsStateWithLifecycle(initialValue = "")
                Selectable(
                    enabled = accounts.isNotEmpty(),
                    title = stringResource(id = R.string.default_sync_cloud),
                    value = stringResource(id = R.string.default_sync_cloud_desc),
                    current = defaultCloudName.ifEmpty { stringResource(id = R.string.not_selected) }
                ) {
                    val items = accounts.map { cloud ->
                        DialogRadioItem(enum = Any(), title = cloud.name, desc = cloud.user)
                    }
                    val defaultIndex = accounts.indexOfFirst { it.name == defaultCloudName }.coerceAtLeast(0)
                    val (state, selectedIndex) = dialogState.select(
                        title = context.getString(R.string.default_sync_cloud),
                        defIndex = defaultIndex,
                        items = items,
                    )
                    if (state.isConfirm) {
                        context.saveDefaultSyncCloud(accounts[selectedIndex].name)
                    }
                }

                val uploadRetries by context.readCloudUploadRetries().collectAsStateWithLifecycle(initialValue = 3)
                Slideable(
                    title = stringResource(id = R.string.cloud_upload_retries),
                    value = uploadRetries.toFloat(),
                    valueRange = 1F..10F,
                    steps = 8,
                    desc = stringResource(id = R.string.args_current_retries, uploadRetries),
                ) {
                    scope.launch {
                        context.saveCloudUploadRetries(it.roundToInt())
                    }
                }

                val syncConcurrency by context.readSyncConcurrency().collectAsStateWithLifecycle(initialValue = 4)
                val boundedSyncConcurrency = syncConcurrency.coerceIn(1, 16)
                Slideable(
                    title = stringResource(id = R.string.sync_concurrency),
                    value = boundedSyncConcurrency.toFloat(),
                    valueRange = 1F..16F,
                    steps = 14,
                    desc = stringResource(id = R.string.args_current_sync_concurrency, boundedSyncConcurrency),
                ) {
                    scope.launch {
                        context.saveSyncConcurrency(it.roundToInt())
                    }
                }
            }

            Title(title = stringResource(id = R.string.advanced)) {
                val items = stringArrayResource(id = R.array.kill_app_options)
                val dialogItems by remember(items) {
                    mutableStateOf(items.mapIndexed { index, s ->
                        DialogRadioItem(enum = KillAppOption.indexOf(index), title = s, desc = null)
                    })
                }
                val currentOption by context.readKillAppOption().collectAsStateWithLifecycle(initialValue = KillAppOption.OPTION_II)
                val currentIndex by remember(currentOption) { mutableIntStateOf(currentOption.ordinal) }
                Selectable(
                    title = stringResource(id = R.string.kill_app_options),
                    value = stringResource(id = R.string.kill_app_options_desc),
                    current = items[currentIndex]
                ) {
                    val (state, selectedIndex) = dialogState.select(
                        title = context.getString(R.string.kill_app_options),
                        defIndex = currentIndex,
                        items = dialogItems
                    )
                    if (state.isConfirm) {
                        context.saveKillAppOption(dialogItems[selectedIndex].enum!!)
                    }
                }

                Switchable(
                    key = KeyCheckKeystore,
                    defValue = true,
                    title = stringResource(id = R.string.check_keystore),
                    checkedText = stringResource(id = R.string.check_keystore_desc),
                )
                Switchable(
                    key = KeyBackupItself,
                    defValue = true,
                    title = stringResource(id = R.string.backup_itself),
                    checkedText = stringResource(id = R.string.backup_itself_desc),
                )
                Switchable(
                    key = KeyBackupConfigs,
                    defValue = true,
                    title = stringResource(id = R.string.backup_configs),
                    checkedText = stringResource(id = R.string.backup_configs_desc),
                )
                /**
                 * Switchable(
                 *     key = KeyCompatibleMode,
                 *     defValue = Build.VERSION.SDK_INT < Build.VERSION_CODES.P,
                 *     title = stringResource(id = R.string.compatible_mode),
                 *     checkedText = stringResource(id = R.string.compatible_mode_desc),
                 * )
                 */
                Switchable(
                    key = KeyFollowSymlinks,
                    defValue = false,
                    title = stringResource(id = R.string.follow_symlinks),
                    checkedText = stringResource(id = R.string.follow_symlinks_desc),
                )
            }
            InnerBottomSpacer(innerPadding = it)
        }
    }
}
