package com.xayah.core.service.medium.restore

import android.annotation.SuppressLint
import com.xayah.core.data.repository.BackupEngineRepository
import com.xayah.core.datastore.readResetRestoreList
import com.xayah.core.datastore.saveLastRestoreTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.RESTORE_SOURCE_ARG
import com.xayah.core.model.RestoreSource
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.of
import com.xayah.core.service.R
import com.xayah.core.service.medium.AbstractMediumService
import com.xayah.core.service.util.MediumRestoreUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractRestoreService : AbstractMediumService() {
    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_preparations),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.NECESSARY_PREPARATIONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_remaining_data_processing),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    @SuppressLint("StringFormatInvalid")
    override suspend fun onInitializing() {
        val medium = getMedium()
        medium.forEach { media ->
            mMediaEntities.add(
                TaskDetailMediaEntity(
                    taskId = mTaskEntity.id,
                    mediaEntity = media,
                    mediaInfo = Info(title = mContext.getString(R.string.args_restore, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.restoring), mContext.getString(R.string.preprocessing))
    }

    abstract suspend fun getMedium(): List<MediaEntity>
    abstract suspend fun restore(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String)
    protected open suspend fun clear() {}

    protected abstract val mMediumRestoreUtil: MediumRestoreUtil
    protected abstract val mBackupEngineRepo: BackupEngineRepository

    protected val mRestoreSource: RestoreSource
        get() = RestoreSource.of(mBoundIntent?.getStringExtra(RESTORE_SOURCE_ARG))
    protected val mRepositorySource: Boolean
        get() = mRestoreSource == RestoreSource.REPOSITORY

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                entity.update(progress = 1f, state = OperationState.DONE)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.MEDIA), availableBytes = mTaskRepo.getAvailableBytes(OpType.RESTORE), totalBytes = mTaskRepo.getTotalBytes(OpType.RESTORE), totalCount = mMediaEntities.size)
        log { "Task count: ${mMediaEntities.size}." }

        mMediaEntities.forEachIndexed { index, media ->
            executeAtLeast {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    media.mediaEntity.name,
                    mMediaEntities.size,
                    index
                )
                log { "Current media: ${media.mediaEntity}" }

                media.update(state = OperationState.PROCESSING)
                val m = media.mediaEntity
                val srcDir = "${mFilesDir}/${m.archivesRelativeDir}"
                if (mRepositorySource) {
                    val snapshotId = m.repositorySnapshotId.ifBlank { BackupEngineRepository.LEGACY_LATEST_SNAPSHOT_ID }
                    val result = mBackupEngineRepo.restoreMedia(m, m.path, snapshotId)
                    if (result.isSuccess) {
                        media.update(progress = 1f, state = OperationState.DONE)
                    } else {
                        media.update(progress = 1f, state = OperationState.ERROR, log = result.toString())
                    }
                } else {
                    restore(m = m, t = media, srcDir = srcDir)
                }

                if (media.isSuccess) {
                    media.update(mediaEntity = m)
                    mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                } else {
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                media.update(state = if (media.isSuccess) OperationState.DONE else OperationState.ERROR)
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                if (mContext.readResetRestoreList().first() && mTaskEntity.failureCount == 0) {
                    mMediaDao.clearActivated(OpType.RESTORE)
                }
                val isSuccess = runCatchingOnService { clear() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastRestoreTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.restore_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}
