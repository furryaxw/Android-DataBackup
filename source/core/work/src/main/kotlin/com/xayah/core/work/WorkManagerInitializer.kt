package com.xayah.core.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.xayah.core.data.repository.CLOUD_UPLOAD_WORK_NAME
import com.xayah.core.data.repository.FAST_INIT_AND_UPDATE_APPS_WORK_NAME
import com.xayah.core.data.repository.FAST_INIT_AND_UPDATE_FILES_WORK_NAME
import com.xayah.core.data.repository.FULL_INIT_AND_UPDATE_APPS_WORK_NAME
import com.xayah.core.data.repository.FULL_INIT_WORK_NAME
import com.xayah.core.work.workers.CloudUploadWorker
import com.xayah.core.work.workers.AppsFastInitWorker
import com.xayah.core.work.workers.AppsFastUpdateWorker
import com.xayah.core.work.workers.AppsInitWorker
import com.xayah.core.work.workers.AppsUpdateWorker
import com.xayah.core.work.workers.FilesUpdateWorker

object WorkManagerInitializer {
    /**
     * Fully initialize at app startup
     */
    fun fullInitialize(context: Context, regular: Boolean = true) {
        WorkManager.getInstance(context)
            .beginUniqueWork(FULL_INIT_WORK_NAME, ExistingWorkPolicy.KEEP, AppsInitWorker.buildRequest())
            .then(AppsUpdateWorker.buildRequest(regular))
            .then(FilesUpdateWorker.buildRequest())
            .enqueue()
    }

    /**
     * Fully initialize, update apps
     */
    fun fullInitializeAndUpdateApps(context: Context, regular: Boolean = false) {
        WorkManager.getInstance(context)
            .beginUniqueWork(FULL_INIT_AND_UPDATE_APPS_WORK_NAME, ExistingWorkPolicy.KEEP, AppsInitWorker.buildRequest())
            .then(AppsUpdateWorker.buildRequest(regular))
            .enqueue()
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps and update newly installed apps
     */
    fun fastInitializeAndUpdateApps(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(FAST_INIT_AND_UPDATE_APPS_WORK_NAME, ExistingWorkPolicy.KEEP, AppsFastInitWorker.buildRequest())
            .then(AppsFastUpdateWorker.buildRequest())
            .enqueue()
    }

    fun fastInitializeAndUpdateFiles(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(FAST_INIT_AND_UPDATE_FILES_WORK_NAME, ExistingWorkPolicy.KEEP, FilesUpdateWorker.buildRequest())
    }

    fun scheduleCloudUpload(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(CLOUD_UPLOAD_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, CloudUploadWorker.buildPeriodicRequest())
    }

    fun cancelCloudUpload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CLOUD_UPLOAD_WORK_NAME)
    }
}
