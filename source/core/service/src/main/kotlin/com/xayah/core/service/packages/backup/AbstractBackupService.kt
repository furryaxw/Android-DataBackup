package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.BackupEngineRepository
import com.xayah.core.data.repository.BackupRetryRepository
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.readSelectionType
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.SelectionType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.get
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.service.model.NecessaryInfo
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractBackupService : AbstractPackagesService() {
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
                title = mContext.getString(R.string.backup_itself),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.BACKUP_ITSELF
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.save_icons),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.SAVE_ICONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
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
        val retryPackages = mBackupRetryRepo.queryRetryPackages()
        isPackageRetry = retryPackages.isNotEmpty()
        val packages = if (isPackageRetry) retryPackages else mPackageRepo.queryActivated(OpType.BACKUP)
        packages.forEach { pkg ->
            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun clear() {}

    protected abstract val mPackagesBackupUtil: PackagesBackupUtil
    protected abstract val mBackupEngineRepo: BackupEngineRepository
    protected abstract val mBackupRetryRepo: BackupRetryRepository

    private var isPackageRetry = false

    private suspend fun TaskDetailPackageEntity.markUnfinishedAsError(message: String) {
        listOf(
            DataType.PACKAGE_APK,
            DataType.PACKAGE_USER,
            DataType.PACKAGE_USER_DE,
            DataType.PACKAGE_DATA,
            DataType.PACKAGE_OBB,
            DataType.PACKAGE_MEDIA,
        ).forEach { type ->
            val state = get(type).state
            if (state != OperationState.DONE && state != OperationState.SKIP) {
                update(dataType = type, progress = 1f, state = OperationState.ERROR, log = message)
            }
        }
    }

    private suspend fun PackageEntity.repositoryDataSelected(dataType: DataType): Boolean = when (mContext.readSelectionType().first()) {
        SelectionType.DEFAULT -> when (dataType) {
            DataType.PACKAGE_APK -> apkSelected
            DataType.PACKAGE_USER -> userSelected
            DataType.PACKAGE_USER_DE -> userDeSelected
            DataType.PACKAGE_DATA -> dataSelected
            DataType.PACKAGE_OBB -> obbSelected
            DataType.PACKAGE_MEDIA -> mediaSelected
            else -> false
        }
        SelectionType.APK -> dataType == DataType.PACKAGE_APK
        SelectionType.DATA -> dataType != DataType.PACKAGE_APK
        SelectionType.BOTH -> true
    }

    private suspend fun PackageEntity.repositorySourcePath(dataType: DataType): String {
        return if (dataType == DataType.PACKAGE_APK) {
            mRootService.getPackageSourceDir(packageName, userId).firstOrNull()?.let { PathUtil.getParentPath(it) }.orEmpty()
        } else {
            val srcDir = mPackageRepo.getDataSrcDir(dataType, userId)
            mPackageRepo.getDataSrc(srcDir, packageName)
        }
    }

    private lateinit var necessaryInfo: NecessaryInfo

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                /**
                 * Somehow the input methods and accessibility services
                 * will be changed after backing up on some devices,
                 * so we restore them manually.
                 */
                necessaryInfo = NecessaryInfo(inputMethods = PreparationUtil.getInputMethods().outString.trim(), accessibilityServices = PreparationUtil.getAccessibilityServices().outString.trim())
                log { "InputMethods: ${necessaryInfo.inputMethods}." }
                log { "AccessibilityServices: ${necessaryInfo.accessibilityServices}." }

                log { "Trying to create: $mConfigsDir." }
                mRootService.mkdirs(mConfigsDir)
                val isSuccess = runCatchingOnService { onTargetDirsCreated() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        // createTargetDirs() before readStatFs().
        mTaskEntity.update(rawBytes = getRawBytes(), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mPkgEntities.size)
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        mPkgEntities.forEachIndexed { index, pkg ->
            var counted = false
            runCatching {
                executeAtLeast {
                    NotificationUtil.notify(
                        mContext,
                        mNotificationBuilder,
                        mContext.getString(R.string.backing_up),
                        pkg.packageEntity.packageInfo.label,
                        mPkgEntities.size,
                        index
                    )
                    log { "Current package: ${pkg.packageEntity}" }

                    killApp(killAppOption, pkg)

                    pkg.update(state = OperationState.PROCESSING)
                    val p = pkg.packageEntity
                    var restoreEntity = mPackageDao.query(p.packageName, OpType.RESTORE, p.userId, p.preserveId, p.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                    val restoreSnapshotInfo = (restoreEntity?.snapshotInfo ?: p.snapshotInfo).copy()
                    val repositoryEngineAvailable = mBackupEngineRepo.shouldUseRepositoryEngine()
                    restoreSnapshotInfo.repositorySource = true
                    if (repositoryEngineAvailable) {
                            var allSnapshotsOk = true
                            listOf(
                                DataType.PACKAGE_APK, DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE,
                                DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA,
                            ).forEach { type ->
                                if (p.repositoryDataSelected(type).not()) {
                                    pkg.update(dataType = type, state = OperationState.SKIP, progress = 1f)
                                    return@forEach
                                }

                                val src = p.repositorySourcePath(type)
                                if (src.isBlank() || mRootService.exists(src).not()) {
                                    val required = type == DataType.PACKAGE_APK || type == DataType.PACKAGE_USER
                                    val message = "Not exist: $src"
                                    pkg.update(dataType = type, state = if (required) OperationState.ERROR else OperationState.SKIP, progress = 1f, log = message)
                                    allSnapshotsOk = allSnapshotsOk && required.not()
                                    return@forEach
                                }

                                val result = mBackupEngineRepo.snapshotDataType(p, type, src)
                                if (result is com.xayah.core.data.repository.RepositorySnapshotResult.Success) {
                                    restoreSnapshotInfo.setSnapshotId(type, result.snapshotId)
                                    pkg.update(dataType = type, state = OperationState.DONE, progress = 1f)
                                } else {
                                    pkg.update(dataType = type, state = OperationState.ERROR, log = result.toString())
                                    allSnapshotsOk = false
                                }
                            }
                            if (p.permissionSelected) mPackagesBackupUtil.backupPermissions(p = p)
                            if (p.ssaidSelected) mPackagesBackupUtil.backupSsaid(p = p)
                            if (allSnapshotsOk.not()) {
                                log { "Repository snapshot failed for ${p.packageName}" }
                            }
                    } else {
                        pkg.markUnfinishedAsError("Repository engine is unavailable.")
                    }

                    if (pkg.isSuccess) {
                            // Save config
                            p.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                            val id = restoreEntity?.id ?: 0
                            restoreEntity = p.copy(
                                id = id,
                                indexInfo = p.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir),
                                extraInfo = p.extraInfo.copy(activated = false),
                                snapshotInfo = restoreSnapshotInfo,
                            )
                            mPackageDao.upsert(restoreEntity)
                            mPackageDao.upsert(p)
                            pkg.update(packageEntity = p)
                            mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                            counted = true
                        } else {
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                            counted = true
                        }
                    pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
                }
            }.onFailure { throwable ->
                val message = throwable.stackTraceToString()
                log { "Failed to back up ${pkg.packageEntity.packageName}: $message" }
                pkg.markUnfinishedAsError(message)
                pkg.update(state = OperationState.ERROR)
                if (counted.not()) {
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                if (mContext.readBackupItself().first()) {
                    log { "Backup itself enabled." }
                    mCommonBackupUtil.backupItself(dstDir = mRootDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onItselfSaved(path = mCommonBackupUtil.getItselfDst(mRootDir), entity = entity)
                        }
                    }
                    entity.update(progress = 1f)
                } else {
                    entity.update(progress = 1f, state = OperationState.SKIP)
                }
            }

            ProcessingInfoType.SAVE_ICONS -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.save_icons)
                )
                mPackagesBackupUtil.backupIcons(dstDir = mConfigsDir).apply {
                    entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                    if (isSuccess) {
                        onIconsSaved(path = mPackagesBackupUtil.getIconsDst(mConfigsDir), entity = entity)
                    }
                }
                entity.update(progress = 1f)
            }

            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                var isSuccess = true
                val out = mutableListOf<String>()
                if (mContext.readBackupConfigs().first()) {
                    log { "Backup configs enabled." }
                    mCommonBackupUtil.backupConfigs(dstDir = mConfigsDir).also { result ->
                        if (result.isSuccess.not()) {
                            isSuccess = false
                        }
                        out.add(result.outString)
                        if (result.isSuccess) {
                            onConfigsSaved(path = mCommonBackupUtil.getConfigsDst(mConfigsDir), entity = entity)
                        }
                    }
                }
                entity.update(progress = 0.5f)

                // Restore keyboard and services.
                if (necessaryInfo.inputMethods.isNotEmpty()) {
                    PreparationUtil.setInputMethods(inputMethods = necessaryInfo.inputMethods)
                    log { "InputMethods restored: ${necessaryInfo.inputMethods}." }
                } else {
                    log { "InputMethods is empty, skip restoring." }
                }
                if (necessaryInfo.accessibilityServices.isNotEmpty()) {
                    PreparationUtil.setAccessibilityServices(accessibilityServices = necessaryInfo.accessibilityServices)
                    log { "AccessibilityServices restored: ${necessaryInfo.accessibilityServices}." }
                } else {
                    log { "AccessibilityServices is empty, skip restoring." }
                }
                if (mContext.readResetBackupList().first() && mTaskEntity.failureCount == 0 && isPackageRetry.not()) {
                    mPackageDao.clearActivated(OpType.BACKUP)
                }
                if (runCatchingOnService { clear() }.not()) {
                    isSuccess = false
                }
                entity.set(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        if (isPackageRetry) {
            mBackupRetryRepo.clearPackageRetry()
        }
        mContext.saveLastBackupTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.backup_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }

    private fun getRawBytes(): Double {
        var total = 0.0
        mPkgEntities.forEach {
            val pkg = it.packageEntity
            if (pkg.apkSelected) total += pkg.dataStats.apkBytes
            if (pkg.userSelected) total += pkg.dataStats.userBytes
            if (pkg.userDeSelected) total += pkg.dataStats.userDeBytes
            if (pkg.dataSelected) total += pkg.dataStats.dataBytes
            if (pkg.obbSelected) total += pkg.dataStats.obbBytes
            if (pkg.mediaSelected) total += pkg.dataStats.mediaBytes
        }
        return total
    }
}
