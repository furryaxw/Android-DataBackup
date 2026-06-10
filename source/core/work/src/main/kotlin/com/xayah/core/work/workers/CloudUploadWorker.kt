package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
internal class CloudUploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val cloudRepo: CloudRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        if (cloudRepo.uploadPending().isSuccess) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return NotificationUtil.createForegroundInfo(
            appContext,
            NotificationUtil.getProgressNotificationBuilder(appContext),
            appContext.getString(R.string.retry_failed_apps),
            "",
        )
    }

    companion object {
        fun buildPeriodicRequest() = PeriodicWorkRequestBuilder<CloudUploadWorker>(15, TimeUnit.MINUTES)
            .build()
    }
}
