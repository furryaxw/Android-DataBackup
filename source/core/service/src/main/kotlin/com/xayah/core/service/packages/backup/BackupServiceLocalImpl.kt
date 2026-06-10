package com.xayah.core.service.packages.backup

import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.BackupRetryRepository
import com.xayah.core.data.repository.BackupEngineRepository
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.RepositoryLayout
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class BackupServiceLocalImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceLocalImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mBackupRetryRepo: BackupRetryRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    @Inject
    override lateinit var mBackupEngineRepo: BackupEngineRepository

    @Inject
    lateinit var mCloudRepo: CloudRepository

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }

    override suspend fun afterPostProcessing() {
        super.afterPostProcessing()
        runCatching {
            mCloudRepo.refreshLocalRepositoryMeta(RepositoryLayout.fromRoot(mRootDir).repositoryRoot)
        }.onFailure {
            log { "Failed to refresh local repository meta after package backup: ${it.localizedMessage ?: it}" }
        }
    }
}
