package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.data.room.AppDatabase
import app.universal.patch.manager.data.room.profile.PatchProfileEntity
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.manager.InstallerPreferenceTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.File

class PatchProfileRepository(
    db: AppDatabase,
    private val patchOptionInputManager: PatchOptionInputManager
) {
    private val dao = db.patchProfileDao()

    fun profilesFlow(): Flow<List<PatchProfile>> =
        dao.observeAll().map(List<PatchProfileEntity>::toDomain)

    fun profilesForPackageFlow(packageName: String): Flow<List<PatchProfile>> =
        dao.observeForPackage(packageName).map(List<PatchProfileEntity>::toDomain)

    suspend fun createProfile(
        packageName: String,
        appVersion: String?,
        name: String,
        payload: PatchProfilePayload
    ): PatchProfile = patchOptionInputManager.updateReferences {
        val existing = dao.findByPackageAndName(packageName, name)
        if (existing != null) {
            throw DuplicatePatchProfileNameException(packageName, name)
        }
        val sortOrder = (dao.getMaxSortOrder() ?: -1) + 1
        PatchProfileEntity(
            uid = AppDatabase.generateUid(),
            packageName = packageName,
            appVersion = appVersion,
            apkPath = null,
            apkSourcePath = null,
            apkVersion = null,
            useSelectedApkVersion = false,
            autoPatch = false,
            installerToken = null,
            autoInstall = false,
            name = name,
            payload = payload,
            createdAt = System.currentTimeMillis(),
            sortOrder = sortOrder
        ).also { dao.upsert(it) }.toDomain()
    }

    suspend fun deleteProfile(uid: Int) {
        patchOptionInputManager.updateReferences {
            dao.delete(uid)
        }
    }

    suspend fun deleteProfiles(uids: Collection<Int>) {
        if (uids.isEmpty()) return
        patchOptionInputManager.updateReferences {
            dao.delete(uids.toList())
        }
    }

    suspend fun updateProfile(
        uid: Int,
        packageName: String,
        appVersion: String?,
        name: String,
        payload: PatchProfilePayload,
        useSelectedApkVersion: Boolean? = null
    ): PatchProfile? = patchOptionInputManager.updateReferences {
        val existing = dao.get(uid) ?: return@updateReferences null
        val conflicting = dao.findByPackageAndName(packageName, name)
        if (conflicting != null && conflicting.uid != uid) {
            throw DuplicatePatchProfileNameException(packageName, name)
        }
        val entity = existing.copy(
            packageName = packageName,
            appVersion = appVersion,
            name = name,
            payload = payload.copy(signatureWorkflow = existing.payload.signatureWorkflow),
            useSelectedApkVersion = useSelectedApkVersion ?: existing.useSelectedApkVersion,
            autoPatch = existing.autoPatch,
            sortOrder = existing.sortOrder
        )
        dao.upsert(entity)
        entity.toDomain()
    }

    suspend fun updateRemappedPayload(
        uid: Int,
        expectedPayload: PatchProfilePayload,
        payload: PatchProfilePayload
    ): Boolean = patchOptionInputManager.updateReferences {
        dao.updateRemappedPayload(uid, expectedPayload, payload)
    }

    suspend fun updateProfileApk(
        uid: Int,
        apkPath: String?,
        apkVersion: String?,
        apkSourcePath: String?,
        appVersion: String?,
        useSelectedApkVersion: Boolean? = null
    ): PatchProfile? {
        val existing = dao.get(uid) ?: return null
        val entity = existing.copy(
            appVersion = appVersion,
            apkPath = apkPath,
            apkSourcePath = apkSourcePath,
            apkVersion = apkVersion,
            useSelectedApkVersion = useSelectedApkVersion ?: existing.useSelectedApkVersion
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun updateProfileSignatureWorkflow(
        uid: Int,
        workflow: PatchProfilePayload.SignatureWorkflow
    ): PatchProfile? {
        val existing = dao.get(uid) ?: return null
        val entity = existing.copy(
            payload = existing.payload.copy(signatureWorkflow = workflow)
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun updateProfileAutoPatch(uid: Int, enabled: Boolean): PatchProfile? {
        val existing = dao.get(uid) ?: return null
        val entity = existing.copy(autoPatch = enabled)
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun updateProfileInstaller(
        uid: Int,
        installerToken: String?,
        autoInstall: Boolean
    ): PatchProfile? {
        val existing = dao.get(uid) ?: return null
        val normalizedInstallerToken = normalizePatchProfileInstallerToken(installerToken)
        val entity = existing.copy(
            installerToken = normalizedInstallerToken,
            autoInstall = autoInstall && normalizedInstallerToken != null
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun migrateLegacyShizukuInstallerTokens() {
        patchOptionInputManager.updateReferences {
            dao.getAll()
                .filter { it.installerToken == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY }
                .forEach { entity ->
                    dao.upsert(
                        entity.copy(installerToken = InstallerPreferenceTokens.SHIZUKU)
                    )
                }
        }
    }

    suspend fun getProfile(uid: Int): PatchProfile? = dao.get(uid)?.toDomain()

    suspend fun exportProfiles(): List<PatchProfileExportEntry> =
        dao.getAll().map(PatchProfileEntity::toExportEntry)

    suspend fun importProfiles(entries: Collection<PatchProfileExportEntry>): ImportProfilesResult {
        if (entries.isEmpty()) return ImportProfilesResult(0, 0, 0)
        return patchOptionInputManager.updateReferences {
            var imported = 0
            var updated = 0
            var skipped = 0
            var nextSortOrder = (dao.getMaxSortOrder() ?: -1) + 1
            for (entry in entries) {
                val normalizedInstallerToken = normalizePatchProfileInstallerToken(entry.installerToken)
                val existing = dao.findByPackageAndName(entry.packageName, entry.name)
                if (existing != null) {
                    val updatedEntity = existing.copy(
                        appVersion = entry.appVersion,
                        useSelectedApkVersion = entry.useSelectedApkVersion,
                        autoPatch = entry.autoPatch,
                        installerToken = normalizedInstallerToken,
                        autoInstall = entry.autoInstall && normalizedInstallerToken != null,
                        payload = entry.payload,
                        createdAt = entry.createdAt ?: existing.createdAt
                    )
                    if (updatedEntity != existing) {
                        dao.upsert(updatedEntity)
                        updated++
                    } else {
                        skipped++
                    }
                    continue
                }
                val entity = PatchProfileEntity(
                    uid = AppDatabase.generateUid(),
                    packageName = entry.packageName,
                    appVersion = entry.appVersion,
                    apkPath = null,
                    apkSourcePath = null,
                    apkVersion = null,
                    useSelectedApkVersion = entry.useSelectedApkVersion,
                    autoPatch = entry.autoPatch,
                    installerToken = normalizedInstallerToken,
                    autoInstall = entry.autoInstall && normalizedInstallerToken != null,
                    name = entry.name,
                    payload = entry.payload,
                    createdAt = entry.createdAt ?: System.currentTimeMillis(),
                    sortOrder = nextSortOrder
                )
                dao.upsert(entity)
                imported++
                nextSortOrder += 1
            }
            ImportProfilesResult(imported, updated, skipped)
        }
    }

    suspend fun reorderProfiles(orderedUids: List<Int>) {
        orderedUids.forEachIndexed { index, uid ->
            dao.updateSortOrder(uid, index)
        }
    }
}

data class PatchProfile(
    val uid: Int,
    val packageName: String,
    val appVersion: String?,
    val apkPath: String?,
    val apkSourcePath: String?,
    val apkVersion: String?,
    val useSelectedApkVersion: Boolean,
    val autoPatch: Boolean,
    val installerToken: String?,
    val autoInstall: Boolean,
    val name: String,
    val createdAt: Long,
    val payload: PatchProfilePayload
)

@Serializable
data class PatchProfileExportEntry(
    val name: String,
    val packageName: String,
    val appVersion: String?,
    val useSelectedApkVersion: Boolean = false,
    val autoPatch: Boolean = false,
    val installerToken: String? = null,
    val autoInstall: Boolean = false,
    val createdAt: Long?,
    val payload: PatchProfilePayload
)

data class ImportProfilesResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int
)

fun resolvePatchProfileAppVersion(
    appVersion: String?,
    apkPath: String?,
    apkVersion: String?,
    useSelectedApkVersion: Boolean
): String? {
    val hasAvailableApk = apkPath?.let(::File)?.exists() == true
    return when {
        useSelectedApkVersion && hasAvailableApk ->
            apkVersion?.takeIf { it.isNotBlank() } ?: appVersion?.takeIf { it.isNotBlank() }
        appVersion?.isNotBlank() == true -> appVersion
        else -> null
    }
}

internal fun normalizePatchProfileInstallerToken(installerToken: String?): String? =
    if (installerToken == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY) {
        InstallerPreferenceTokens.SHIZUKU
    } else {
        installerToken
    }

private fun PatchProfileEntity.toDomain() = PatchProfile(
    uid = uid,
    packageName = packageName,
    appVersion = appVersion,
    apkPath = apkPath,
    apkSourcePath = apkSourcePath,
    apkVersion = apkVersion,
    useSelectedApkVersion = useSelectedApkVersion,
    autoPatch = autoPatch,
    installerToken = normalizePatchProfileInstallerToken(installerToken),
    autoInstall = autoInstall,
    name = name,
    createdAt = createdAt,
    payload = payload
)

class DuplicatePatchProfileNameException(
    val packageName: String,
    val profileName: String
) : IllegalArgumentException("Duplicate patch profile name \"$profileName\" for package $packageName")

private fun List<PatchProfileEntity>.toDomain() = map(PatchProfileEntity::toDomain)

private fun PatchProfileEntity.toExportEntry() = PatchProfileExportEntry(
    name = name,
    packageName = packageName,
    appVersion = appVersion,
    useSelectedApkVersion = useSelectedApkVersion,
    autoPatch = autoPatch,
    installerToken = normalizePatchProfileInstallerToken(installerToken),
    autoInstall = autoInstall,
    createdAt = createdAt,
    payload = payload
)
