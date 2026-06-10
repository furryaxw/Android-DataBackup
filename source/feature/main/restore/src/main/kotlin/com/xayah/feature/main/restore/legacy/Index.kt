package com.xayah.feature.main.restore.legacy

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.feature.main.restore.R
import com.xayah.feature.main.restore.RestoreScaffold

@SuppressLint("StringFormatInvalid")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageLegacyBackups() {
    val navController = LocalNavController.current!!
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<LegacyViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(LegacyUiIntent.Refresh)
    }

    RestoreScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.legacy_backups),
        actions = {
            OutlinedButton(
                enabled = uiState.isLoading.not(),
                onClick = { viewModel.emitIntentOnIO(LegacyUiIntent.Reload) },
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text(text = stringResource(id = R.string.reload))
            }
            Button(
                enabled = uiState.isLoading.not() && uiState.summary.hasAny,
                onClick = { viewModel.emitIntentOnIO(LegacyUiIntent.ImportToRepository) },
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Text(text = stringResource(id = R.string.import_to_repository))
            }
        }
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Title(title = stringResource(id = R.string.overlook)) {
                    Clickable(
                        enabled = uiState.isLoading.not(),
                        title = stringResource(id = R.string.apps),
                        value = stringResource(id = R.string.args_apps_backed_up, uiState.summary.appsCount),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                    ) {
                        viewModel.emitIntentOnIO(LegacyUiIntent.ToAppList(navController))
                    }
                    Clickable(
                        enabled = uiState.isLoading.not(),
                        title = stringResource(id = R.string.files),
                        value = stringResource(id = R.string.args_files_backed_up, uiState.summary.filesCount),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                    ) {
                        viewModel.emitIntentOnIO(LegacyUiIntent.ToFileList(navController))
                    }
                }
            }

            item {
                Title(title = stringResource(id = R.string.directory_structure)) {
                    LegacyLayoutRow(title = "1.0.x", present = uiState.summary.has10xLayout)
                    LegacyLayoutRow(title = "1.1.x", present = uiState.summary.has11xLayout)
                    LegacyLayoutRow(title = "1.2.x", present = uiState.summary.has12xLayout)
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .paddingHorizontal(SizeTokens.Level16)
                        .paddingTop(SizeTokens.Level16),
                ) {
                    if (uiState.isLoading) {
                        LinearProgressIndicator()
                    }
                    Text(text = uiState.text)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalAnimationApi::class)
private fun LegacyLayoutRow(title: String, present: Boolean) {
    Clickable(
        enabled = false,
        title = title,
        value = stringResource(id = if (present) R.string.existing_files else R.string.not_exist),
        leadingIcon = Icons.Rounded.Archive,
    )
}
