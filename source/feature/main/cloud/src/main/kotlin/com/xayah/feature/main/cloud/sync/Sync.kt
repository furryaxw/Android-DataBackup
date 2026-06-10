package com.xayah.feature.main.cloud.sync

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.FilledTonalIconTextButton
import com.xayah.core.ui.component.LabelSmallText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.OverviewCard
import com.xayah.core.ui.component.OutlinedButtonIconTextButton
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.SegmentCircularProgressIndicator
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.feature.main.cloud.CloudScaffold
import com.xayah.feature.main.cloud.R

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageSync() {
    val viewModel = hiltViewModel<SyncViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backToDashboard = {
        navController.navigate(MainRoutes.Dashboard.route) {
            popUpTo(MainRoutes.Dashboard.route) {
                inclusive = false
            }
            launchSingleTop = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.emitIntentOnIO(SyncUiIntent.LoadCached)
    }
    BackHandler {
        backToDashboard()
    }

    CloudScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.sync),
        onBackClick = backToDashboard,
        actions = {
            com.xayah.core.ui.component.IconButton(
                enabled = uiState.isRefreshing.not() && uiState.isProcessing.not(),
                icon = Icons.Rounded.Refresh,
                onClick = {
                    viewModel.emitIntentOnIO(SyncUiIntent.Refresh)
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            SyncStatusCard(
                modifier = Modifier.padding(horizontal = SizeTokens.Level16),
                uiState = uiState,
                viewModel = viewModel,
            )

            Title(title = stringResource(id = R.string.local_repository)) {
                Clickable(
                    readOnly = true,
                    title = stringResource(id = R.string.local_repository),
                    value = uiState.localRepository,
                    leadingContent = {
                        Icon(imageVector = Icons.Rounded.Upload, contentDescription = null)
                    },
                )
                Clickable(
                    readOnly = true,
                    title = stringResource(id = R.string.existing_files),
                    value = "${uiState.localFileCount} / ${viewModel.bytesText(uiState.localBytes)}",
                    leadingContent = {
                        Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open), contentDescription = null)
                    },
                )
            }

            Title(title = stringResource(id = R.string.cloud_repository)) {
                Clickable(
                    readOnly = true,
                    enabled = accounts.isNotEmpty(),
                    title = uiState.selectedCloudName.ifEmpty { stringResource(id = R.string.no_available_cloud_accounts) },
                    value = uiState.selectedCloudRepository,
                    leadingContent = {
                        Icon(imageVector = Icons.Outlined.Cloud, contentDescription = null)
                    },
                )
                Clickable(
                    readOnly = true,
                    enabled = uiState.selectedCloudName.isNotEmpty(),
                    title = stringResource(id = R.string.existing_files),
                    value = "${uiState.cloudFileCount} / ${viewModel.bytesText(uiState.cloudBytes)}",
                    leadingContent = {
                        Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open), contentDescription = null)
                    },
                )
            }

            Title(title = stringResource(id = R.string.sync)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SizeTokens.Level24),
                    horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                ) {
                    FilledTonalIconTextButton(
                        modifier = Modifier.weight(1f),
                        enabled = uiState.selectedCloudName.isNotEmpty() && uiState.isProcessing.not(),
                        icon = Icons.Rounded.Upload,
                        text = stringResource(id = R.string.force_push),
                    ) {
                        viewModel.emitIntentOnIO(SyncUiIntent.ForcePush)
                    }
                    FilledTonalIconTextButton(
                        modifier = Modifier.weight(1f),
                        enabled = uiState.selectedCloudName.isNotEmpty() && uiState.isProcessing.not(),
                        icon = Icons.Rounded.Download,
                        text = stringResource(id = R.string.force_pull),
                    ) {
                        dialogState.confirm(
                            title = context.getString(R.string.force_pull),
                            text = context.getString(R.string.force_pull_warning),
                        ) {
                            viewModel.emitIntentOnIO(SyncUiIntent.ForcePull)
                        }
                    }
                }

                if (uiState.isProcessing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SizeTokens.Level24),
                        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
                    ) {
                        OutlinedButtonIconTextButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Pause,
                            text = stringResource(id = R.string.pause),
                        ) {
                            viewModel.emitIntentOnIO(SyncUiIntent.Pause)
                        }
                        OutlinedButtonIconTextButton(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Cancel,
                            text = stringResource(id = R.string.cancel),
                        ) {
                            viewModel.emitIntentOnIO(SyncUiIntent.Cancel)
                        }
                    }
                }
            }

            if (uiState.errors.isNotEmpty()) {
                Title(title = stringResource(id = R.string.failed)) {
                    uiState.errors.take(5).forEach { error ->
                        Clickable(
                            readOnly = true,
                            title = stringResource(id = R.string.log),
                            value = error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun SyncStatusCard(
    modifier: Modifier,
    uiState: SyncUiState,
    viewModel: SyncViewModel,
) {
    val fileProgress = if (uiState.totalFiles == 0) 0f else uiState.processedFiles.toFloat() / uiState.totalFiles
    val actionLabel = when (uiState.operation) {
        SyncOperation.PUSH -> stringResource(id = R.string.force_push)
        SyncOperation.PULL -> stringResource(id = R.string.force_pull)
        null -> when {
            uiState.isPaused -> stringResource(id = R.string.sync_paused)
            uiState.isCancelled -> stringResource(id = R.string.sync_cancelled)
            uiState.isRefreshing -> stringResource(id = R.string.refresh)
            else -> stringResource(id = R.string.sync)
        }
    }
    val updatedAt = if (uiState.cloudStatsUpdatedAt == 0L) {
        stringResource(id = R.string.never)
    } else {
        DateUtil.formatTimestamp(uiState.cloudStatsUpdatedAt, DateUtil.PATTERN_FINISH)
    }

    OverviewCard(
        modifier = modifier,
        title = stringResource(id = R.string.sync),
        icon = if (uiState.operation == SyncOperation.PULL) Icons.Rounded.Download else Icons.Rounded.Upload,
        colorContainer = ThemedColorSchemeKeyTokens.SecondaryContainer,
        onColorContainer = ThemedColorSchemeKeyTokens.OnSecondaryContainer,
        actionIcon = if (uiState.isRefreshing) Icons.Rounded.Refresh else null,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PackageIconImage(
                        icon = if (uiState.operation == SyncOperation.PULL) Icons.Rounded.Download else Icons.Rounded.Upload,
                        packageName = "",
                        inCircleShape = true,
                        size = SizeTokens.Level128,
                    )
                    SegmentCircularProgressIndicator(
                        size = SizeTokens.Level152,
                        segments = 24,
                        progress = when {
                            uiState.isProcessing -> fileProgress.coerceIn(0f, 1f)
                            uiState.totalFiles > 0 -> 1f
                            else -> 0f
                        },
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
                ) {
                    TitleLargeText(
                        text = actionLabel,
                        color = ThemedColorSchemeKeyTokens.OnSurface.value,
                        fontWeight = FontWeight.SemiBold,
                    )
                    BodyMediumText(
                        text = "${stringResource(id = R.string.transfer_progress)}: ${uiState.processedFiles}/${uiState.totalFiles}",
                        color = ThemedColorSchemeKeyTokens.OnSurface.value,
                    )
                    BodyMediumText(
                        text = "${viewModel.bytesText(uiState.transferredBytes)} / ${viewModel.bytesText(uiState.totalBytes)}",
                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    )
                    BodyMediumText(
                        text = "${stringResource(id = R.string.transfer_speed)}: ${viewModel.speedText(uiState.transferSpeedBytesPerSecond)}",
                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    )
                    LabelSmallText(
                        text = stringResource(id = R.string.cloud_state_cached_at, updatedAt),
                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    )
                }
            }

            if (uiState.currentPath.isNotEmpty()) {
                BodyMediumText(
                    modifier = Modifier.padding(top = SizeTokens.Level8),
                    text = uiState.currentPath,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    maxLines = 3,
                )
            }

            if (uiState.message.isNotEmpty() && uiState.isProcessing.not()) {
                BodyMediumText(
                    modifier = Modifier.padding(top = SizeTokens.Level8),
                    text = uiState.message,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    maxLines = 3,
                )
            }
        },
    )
}
