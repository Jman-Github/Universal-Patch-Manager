package app.universal.patch.manager.domain.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import app.revanced.library.ApkSigner as RevancedApkSigner
import com.android.apksig.ApkSigner as AndroidApkSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import app.universal.patch.manager.patcher.split.SplitApkFiles
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.Provider
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.EncryptedPrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider

class KeystoreManager(app: Application, private val prefs: PreferencesManager) {
    companion object Constants {
        /**
         * Default alias and password for newly generated manager keystores.
         */
        const val DEFAULT_ALIAS = "alias"
        const val DEFAULT_PASSWORD = "password"
        const val DEFAULT_KEY_PASSWORD = DEFAULT_PASSWORD
        const val LEGACY_DEFAULT_ALIAS = "ReVanced"
        const val LEGACY_DEFAULT_PASSWORD = "s3cur3p@ssw0rd"

        /**
         * Kept for source compatibility with older code paths that referenced a single default value.
         */
        const val DEFAULT = DEFAULT_ALIAS
        private const val LOG_TAG = "KeystoreManager"
        private const val KEYSTORE_FILE_NAME = "upm_keystore.keystore"
        private const val LEGACY_KEYSTORE_FILE_NAME = "manager.keystore"
        // 9999-12-31T23:59:59Z. Keep cert validity fixed at the practical max.
        private const val MAX_CERT_NOT_AFTER_MS = 253402300799000L
        private val maxCertificateNotAfter = Date(MAX_CERT_NOT_AFTER_MS)
    }

    private val keystorePath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve(KEYSTORE_FILE_NAME)
    private val credentialsPath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve("keystore.txt")
    private val legacySigningKeystorePath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve(LEGACY_KEYSTORE_FILE_NAME)
    private val legacyKeystorePath = app.filesDir.resolve(LEGACY_KEYSTORE_FILE_NAME)
    private val backupKeystorePath = app.getExternalFilesDir("keystore")?.resolve(KEYSTORE_FILE_NAME)
    private val legacyBackupKeystorePath =
        app.getExternalFilesDir("keystore")?.resolve(LEGACY_KEYSTORE_FILE_NAME)
    private val keystoreMutex = Mutex()
    // Code adapted from Morphe, see third-party/NOTICE for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/1088
    private val signerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class SigningKeyCacheKey(
        val fingerprint: String,
        val credentials: KeystoreCredentials
    )
    private data class CachedSigningKey(
        val cacheKey: SigningKeyCacheKey,
        val keyMaterial: KeyMaterial,
        val inPlaceSigner: InPlaceApkSigner
    )
    // Accessed only under keystoreMutex; hash the file so same-size replacements also invalidate it.
    private var cachedSigningKey: CachedSigningKey? = null
    private val appContext = app.applicationContext
    private val bcProvider: Provider by lazy {
        // Use an explicit provider instance only when required (e.g., BKS) to avoid
        // altering global provider order and impacting PKCS12/JKS loading.
        BouncyCastleProvider()
    }

    private data class KeystoreCredentials(
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val type: String?,
        val fingerprint: String?
    )

    enum class KeystoreFormat(
        val label: String,
        val extension: String,
        val keyStoreType: String
    ) {
        BKS("BKS", "bks", "BKS"),
        JKS("JKS", "jks", "JKS"),
        PKCS12("PKCS12", "p12", "PKCS12")
    }

    data class KeystoreBinary(
        val bytes: ByteArray,
        val alias: String,
        val format: KeystoreFormat
    )

    data class KeystoreDiagnostics(
        val keystorePath: String,
        val keystoreSize: Long,
        val credentialsPath: String,
        val credentialsExists: Boolean,
        val backupPath: String?,
        val backupSize: Long?,
        val legacyPath: String,
        val legacySize: Long,
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val type: String?,
        val fingerprint: String?
    )

    private data class KeyMaterial(
        val alias: String,
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>,
        val storeType: String
    )

    private data class LoadedKeyMaterial(
        val keyMaterial: KeyMaterial,
        val storePass: String,
        val keyPass: String
    )

    private suspend fun updatePrefs(
        alias: String,
        storePass: String,
        keyPass: String,
        type: String?,
        fingerprint: String?
    ) {
        cachedSigningKey = null
        prefs.edit {
            prefs.keystoreAlias.value = alias
            prefs.keystorePass.value = storePass
            prefs.keystoreKeyPass.value = keyPass
        }
        writeCredentials(alias, storePass, keyPass, type, fingerprint)
    }

    private suspend fun resolveCredentials(): KeystoreCredentials {
        val fileCreds = readCredentials()
        val alias = fileCreds?.alias?.takeIf { it.isNotBlank() } ?: prefs.keystoreAlias.get()
        val storePass = fileCreds?.storePass?.takeIf { it.isNotBlank() } ?: prefs.keystorePass.get()
        val prefKeyPass = prefs.keystoreKeyPass.get()
        val keyPass = fileCreds?.keyPass?.takeIf { it.isNotBlank() }
            ?: prefKeyPass.takeIf { it.isNotBlank() }
        val defaultKeyPassValues = setOf(DEFAULT_KEY_PASSWORD, LEGACY_DEFAULT_ALIAS)
        val resolvedKeyPass = when {
            fileCreds == null &&
                prefKeyPass in defaultKeyPassValues &&
                storePass.isNotBlank() &&
                storePass !in defaultKeyPassValues -> storePass
            keyPass.isNullOrBlank() -> storePass
            else -> keyPass
        }
        val resolvedStorePass = storePass.takeIf { it.isNotBlank() } ?: resolvedKeyPass
        return KeystoreCredentials(
            alias,
            resolvedStorePass,
            resolvedKeyPass,
            fileCreds?.type,
            fileCreds?.fingerprint
        )
    }

    private suspend fun readCredentials(): KeystoreCredentials? = withContext(Dispatchers.IO) {
        if (!credentialsPath.exists()) return@withContext null
        val props = Properties()
        credentialsPath.inputStream().use { props.load(it) }
        val alias = props.getProperty("alias")?.trim().orEmpty()
        val storePass = props.getProperty("storePassword")?.trim()
            ?: props.getProperty("password")?.trim()
            ?: ""
        val keyPass = props.getProperty("keyPassword")?.trim().orEmpty()
        val type = props.getProperty("type")?.trim()?.takeIf { it.isNotEmpty() }
        val fingerprint = props.getProperty("fingerprint")?.trim()?.takeIf { it.isNotEmpty() }
        if (alias.isEmpty() || (storePass.isEmpty() && keyPass.isEmpty())) return@withContext null
        val resolvedKeyPass = if (keyPass.isBlank()) storePass else keyPass
        KeystoreCredentials(alias, storePass, resolvedKeyPass, type, fingerprint)
    }

    private suspend fun writeCredentials(
        alias: String,
        storePass: String,
        keyPass: String,
        type: String?,
        fingerprint: String?
    ) =
        withContext(Dispatchers.IO) {
            val props = Properties().apply {
                setProperty("alias", alias)
                setProperty("storePassword", storePass)
                setProperty("keyPassword", keyPass)
                setProperty("password", storePass)
                if (!type.isNullOrBlank()) setProperty("type", type)
                if (!fingerprint.isNullOrBlank()) setProperty("fingerprint", fingerprint)
            }
            credentialsPath.outputStream().use { output ->
                props.store(output, "Keystore credentials")
            }
        }

    // Code adapted from Morphe, see third-party/NOTICE for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/1088
    /** Load the existing signing key while patching runs, without failing the patch on preload errors. */
    fun preloadSigner() {
        signerScope.launch {
            try {
                keystoreMutex.withLock {
                    if (keystorePath.isFile && keystorePath.length() > 0L) {
                        signingKey(notifyOnInvalidCredentials = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.d(LOG_TAG, "Signing key preload failed; will retry when signing", error)
            }
        }
    }

    /** Only for patched output: the host requires Android 8+, which supports v2 signatures. */
    suspend fun signPatchedApk(apk: File) = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            val signer = signingKey().inPlaceSigner
            suspend fun signComponent(component: File) {
                try {
                    runInterruptible(Dispatchers.IO) { signer.sign(component, Build.VERSION.SDK_INT) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Preserve recovery for inconsistent ZIP headers from the input.
                    val sanitized = sanitizeZipIfNeeded(component)
                    if (sanitized == component) throw error
                    try {
                        runInterruptible(Dispatchers.IO) {
                            signer.sign(sanitized, Build.VERSION.SDK_INT)
                            Files.move(sanitized.toPath(), component.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        sanitized.delete()
                    }
                }
            }
            if (SplitApkPreparer.isSplitArchive(apk)) {
                val directory = Files.createTempDirectory(apk.parentFile!!.toPath(), "split-sign-").toFile()
                try {
                    val components = runInterruptible(Dispatchers.IO) {
                        SplitApkFiles.extract(apk, directory, SplitApkPreparer.splitApkEntryNames(apk))
                    }
                    // One key and one lock for the whole set: Android requires matching signers.
                    components.forEach { signComponent(it) }
                    runInterruptible(Dispatchers.IO) {
                        val signed = directory.resolve("signed.apks")
                        SplitApkFiles.pack(components, signed)
                        Files.move(signed.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    directory.deleteRecursively()
                }
            } else signComponent(apk)
        }
    }

    suspend fun sign(input: File, output: File) =
        signWithOptions(input, output, preserveSignatureMetadata = false)

    suspend fun signPreservingSignatureMetadata(input: File, output: File) =
        signWithOptions(input, output, preserveSignatureMetadata = true)

    private suspend fun signWithOptions(
        input: File,
        output: File,
        preserveSignatureMetadata: Boolean
    ) = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            try {
                signWithApksig(input, output, preserveSignatureMetadata)
                return@withLock
            } catch (e: Exception) {
                val sanitized = sanitizeZipIfNeeded(input)
                if (sanitized == input) {
                    throw e
                }
                try {
                    signWithApksig(sanitized, output, preserveSignatureMetadata)
                } finally {
                    sanitized.delete()
                }
            }
        }
    }

    suspend fun regenerate() = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            regenerateLocked()
        }
    }

    suspend fun createKeystore(
        alias: String,
        storePass: String,
        keyPass: String,
        format: KeystoreFormat,
        algorithm: SigningKeyAlgorithm = SigningKeyAlgorithm.RSA
    ): KeystoreBinary = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            val normalizedAlias = alias.trim()
            require(normalizedAlias.isNotBlank()) { "Alias is required." }
            val normalizedStorePass = storePass.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Keystore password is required.")
            val normalizedKeyPass = keyPass.takeIf { it.isNotBlank() } ?: normalizedStorePass

            val (privateKey, certificate) = generateSigningKey(normalizedAlias, maxCertificateNotAfter, algorithm)
            val converted = buildKeystoreBytes(
                alias = normalizedAlias,
                keyPass = normalizedKeyPass,
                storePass = normalizedStorePass,
                privateKey = privateKey,
                certificates = listOf(certificate),
                storeType = format.keyStoreType
            )
            KeystoreBinary(converted, normalizedAlias, format)
        }
    }

    suspend fun convertKeystore(
        keystore: InputStream,
        alias: String,
        storePass: String,
        keyPass: String,
        format: KeystoreFormat,
        algorithm: SigningKeyAlgorithm? = null
    ): KeystoreBinary = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            val keystoreData = withContext(Dispatchers.IO) { keystore.readBytes() }
            val normalizedStorePass = storePass.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Keystore password is required.")
            val normalizedKeyPass = keyPass.takeIf { it.isNotBlank() } ?: normalizedStorePass
            val requestedAlias = alias.trim()

            val loaded = tryLoadKeyMaterial(
                keystoreData,
                requestedAlias,
                normalizedStorePass,
                normalizedKeyPass
            ) ?: throw IllegalArgumentException("Wrong keystore credentials.")

            val (convertedKey, convertedCertificates) = convertedSigningKey(
                loaded.keyMaterial.privateKey, loaded.keyMaterial.certificates,
                loaded.keyMaterial.alias, algorithm, maxCertificateNotAfter
            )
            val converted = buildKeystoreBytes(
                alias = loaded.keyMaterial.alias,
                keyPass = normalizedKeyPass,
                storePass = normalizedStorePass,
                privateKey = convertedKey,
                certificates = convertedCertificates,
                storeType = format.keyStoreType
            )
            KeystoreBinary(converted, loaded.keyMaterial.alias, format)
        }
    }

    /**
     * Some APKs (often from third-party downloads) contain malformed ZIP headers that trigger
     * ApkSigner errors like "Data Descriptor presence mismatch". Repackage the archive to fix
     * header inconsistencies before signing.
     */
    private suspend fun sanitizeZipIfNeeded(input: File): File = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File.createTempFile("apk-sanitized-", ".apk", input.parentFile)
            ZipFile(input).use { zip ->
                ZipOutputStream(tempFile.outputStream()).use { zos ->
                    zip.entries().asSequence().forEach { entry ->
                        val cleanEntry = ZipEntry(entry.name).apply {
                            method = entry.method
                            time = entry.time
                            comment = entry.comment
                            size = entry.size
                            compressedSize = -1 // let ZipOutputStream compute
                            crc = entry.crc
                            extra = entry.extra
                        }
                        zos.putNextEntry(cleanEntry)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { inputStream ->
                                BufferedInputStream(inputStream).copyTo(zos)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            tempFile
        }.getOrElse { input }
    }

    suspend fun import(
        alias: String,
        storePass: String,
        keyPass: String,
        keystore: InputStream
    ): Boolean = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            val keystoreData = withContext(Dispatchers.IO) { keystore.readBytes() }
            val resolvedKeyPass = keyPass.takeIf { it.isNotBlank() } ?: storePass

            val loaded = tryLoadKeyMaterial(keystoreData, alias, storePass, resolvedKeyPass)
                ?: run {
                    Log.w(LOG_TAG, "Keystore import failed: unable to load key material for alias=$alias")
                    return@withLock false
                }
            val keyMaterial = loaded.keyMaterial
            val usedStorePass = loaded.storePass
            val usedKeyPass = loaded.keyPass
            val normalizedStorePass =
                if (keyMaterial.storeType == "JKS") usedStorePass.ifBlank { usedKeyPass } else usedStorePass
            val normalizedKeyPass = usedKeyPass.ifBlank { normalizedStorePass }

            val persistedType = if (keyMaterial.storeType == "JKS") "PKCS12" else keyMaterial.storeType
            val persistedBytes = if (keyMaterial.storeType == "JKS") {
                buildPkcs12Keystore(
                    keyMaterial.alias,
                    normalizedKeyPass,
                    normalizedStorePass,
                    keyMaterial.privateKey,
                    keyMaterial.certificates
                )
            } else {
                keystoreData
            }

            val fingerprint = keystoreFingerprint(persistedBytes)
            withContext(Dispatchers.IO) {
                Files.write(keystorePath.toPath(), persistedBytes)
                writeBackupKeystore(persistedBytes)
                deleteLegacyKeystoreCopies()
            }

            updatePrefs(keyMaterial.alias, normalizedStorePass, normalizedKeyPass, persistedType, fingerprint)
            Log.i(LOG_TAG, "Keystore imported (alias=${keyMaterial.alias}, type=$persistedType)")
            true
        }
    }

    fun hasKeystore(): Boolean {
        if (keystorePath.exists() && keystorePath.length() > 0L) return true
        if (backupKeystorePath?.let { it.exists() && it.length() > 0L } == true) return true
        return legacyRestoreCandidates().any { it.exists() && it.length() > 0L }
    }

    fun signingStorageRoots(): List<File> = listOfNotNull(
        keystorePath.parentFile,
        backupKeystorePath?.parentFile,
        legacyBackupKeystorePath?.parentFile,
        legacyKeystorePath,
        appContext.filesDir.resolve("urv_keystore.keystore"),
    ).distinctBy { file ->
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }

    suspend fun clearSigningFiles() = withContext(Dispatchers.IO) {
        keystoreMutex.withLock {
            cachedSigningKey = null
            listOfNotNull(
                keystorePath,
                credentialsPath,
                legacySigningKeystorePath,
                legacyKeystorePath,
                backupKeystorePath,
                legacyBackupKeystorePath,
            ).plus(legacyRestoreCandidates()).distinct().forEach { file ->
                check(!file.exists() || file.deleteRecursively()) {
                    "Failed to delete signing file at ${file.absolutePath}"
                }
            }
        }
    }

    suspend fun export(target: OutputStream) = withContext(Dispatchers.IO) {
        keystoreMutex.withLock {
            requireKeystoreReady()
            Files.copy(keystorePath.toPath(), target)
        }
    }

    suspend fun getDiagnostics(): KeystoreDiagnostics = withContext(Dispatchers.IO) {
        val fileCreds = readCredentials()
        val resolved = resolveCredentials()
        val backupFile = backupKeystorePath
        val legacyFile = legacyRestoreCandidates().firstOrNull { it.exists() && it.length() > 0L }
            ?: legacySigningKeystorePath
        KeystoreDiagnostics(
            keystorePath = keystorePath.absolutePath,
            keystoreSize = if (keystorePath.exists()) keystorePath.length() else 0L,
            credentialsPath = credentialsPath.absolutePath,
            credentialsExists = credentialsPath.exists(),
            backupPath = backupFile?.absolutePath,
            backupSize = backupFile?.takeIf { it.exists() }?.length(),
            legacyPath = legacyFile.absolutePath,
            legacySize = if (legacyFile.exists()) legacyFile.length() else 0L,
            alias = resolved.alias,
            storePass = resolved.storePass,
            keyPass = resolved.keyPass,
            type = fileCreds?.type,
            fingerprint = fileCreds?.fingerprint
        )
    }

    private suspend fun requireKeystoreReady() {
        if (keystorePath.exists() && keystorePath.length() > 0L) return
        Log.d(LOG_TAG, "Keystore missing at ${keystorePath.absolutePath}; attempting restore")
        if (tryRestoreKeystore()) return
        Log.i(LOG_TAG, "Keystore restore unavailable; generating a new keystore")
        regenerateLocked()
    }

    private fun keyStoreTypes(preferred: String?): List<String> {
        val base = listOf("PKCS12", "JKS", "BKS-V1", "BKS")
        val preferredNormalized = preferred?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        return (listOfNotNull(preferredNormalized) + base).distinct()
    }

    private fun keyStoreInstance(type: String, preferBc: Boolean): KeyStore {
        if (preferBc && type.equals("BKS", ignoreCase = true)) {
            bcProvider?.let { provider ->
                return KeyStore.getInstance(type, provider)
            }
        }
        return KeyStore.getInstance(type)
    }

    private fun loadKeyStoreFromBytes(
        keystoreData: ByteArray,
        type: String,
        storePass: CharArray?,
        preferBc: Boolean
    ): KeyStore? {
        val isBks = type.equals("BKS", ignoreCase = true) || type.equals("BKS-V1", ignoreCase = true)
        val providers = if (preferBc && isBks) listOf<Provider?>(bcProvider, null) else listOf(null)
        for (provider in providers) {
            val ks = runCatching {
                if (provider == null) KeyStore.getInstance(type) else KeyStore.getInstance(type, provider)
            }.getOrNull() ?: continue
            val loaded = runCatching {
                ks.load(ByteArrayInputStream(keystoreData), storePass)
                ks
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return null
    }

    private fun tryLoadKeyMaterial(
        keystoreData: ByteArray,
        alias: String,
        storePass: String,
        keyPass: String,
        preferredType: String? = null
    ): LoadedKeyMaterial? {
        val keyCandidates = buildList<CharArray?> {
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }
        if (keyCandidates.isEmpty()) return null
        val storeCandidates = buildList<CharArray?> {
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }
        for (type in keyStoreTypes(preferredType)) {
            for (storeCandidate in storeCandidates) {
                try {
                    val ks = loadKeyStoreFromBytes(keystoreData, type, storeCandidate, preferBc = true)
                        ?: continue
                    val aliases = ks.aliases().toList()
                    val aliasCandidates = when {
                        aliases.isEmpty() -> listOf(alias)
                        alias.isNotBlank() && aliases.contains(alias) -> listOf(alias)
                        else -> aliases
                    }
                    for (candidateAlias in aliasCandidates) {
                        for (keyCandidate in keyCandidates) {
                            val privateKey = runCatching {
                                ks.getKey(candidateAlias, keyCandidate) as? PrivateKey
                            }.getOrNull() ?: continue
                            val certs = ks.getCertificateChain(candidateAlias)
                                ?.mapNotNull { it as? X509Certificate }
                                ?.takeIf { it.isNotEmpty() }
                                ?: continue
                            return LoadedKeyMaterial(
                                KeyMaterial(candidateAlias, privateKey, certs, type),
                                storeCandidate?.concatToString().orEmpty(),
                                keyCandidate?.concatToString().orEmpty()
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Try next store/type candidate.
                }
            }
        }

        val jksMaterial = tryLoadJksKeyMaterial(keystoreData, alias, storePass, keyPass)
        if (jksMaterial != null) {
            return jksMaterial
        }

        return null
    }

    private suspend fun signingKey(
        notifyOnInvalidCredentials: Boolean = true
    ): CachedSigningKey {
        requireKeystoreReady()
        val keystoreData = withContext(Dispatchers.IO) {
            Files.readAllBytes(keystorePath.toPath())
        }
        val fingerprint = keystoreFingerprint(keystoreData)
        var credentials = resolveCredentials()
        val cacheKey = SigningKeyCacheKey(fingerprint, credentials)
        cachedSigningKey?.takeIf { it.cacheKey == cacheKey }?.let { return it }
        cachedSigningKey = null
        if (!credentials.fingerprint.isNullOrBlank() && credentials.fingerprint != fingerprint) {
            throw IllegalArgumentException("Keystore changed. Re-import or regenerate it.")
        }

        fun loadStrict(creds: KeystoreCredentials): StrictLoadResult? {
            val signingType = resolveSigningType(creds) ?: return null
            val allowNullStorePassFallback =
                creds.fingerprint.isNullOrBlank() || creds.storePass.isBlank()
            val typeCandidates = when {
                signingType.equals("BKS", ignoreCase = true) ->
                    listOf("BKS", "BKS-V1")
                signingType.equals("BKS-V1", ignoreCase = true) ->
                    listOf("BKS-V1", "BKS")
                else -> listOf(signingType)
            }
            for (type in typeCandidates.distinct()) {
                val loaded = loadKeyMaterialStrict(
                    keystoreData,
                    creds.alias,
                    creds.storePass,
                    creds.keyPass,
                    type,
                    allowNullStorePassFallback
                )
                if (loaded != null) return loaded
            }
            return null
        }

        var strictResult = loadStrict(credentials)
        if (strictResult == null && credentials.fingerprint.isNullOrBlank()) {
            if (rehydrateCredentials(keystoreData)) {
                credentials = resolveCredentials()
                strictResult = loadStrict(credentials)
            }
        }
        if (strictResult == null) {
            val flexible = tryLoadKeyMaterial(
                keystoreData,
                credentials.alias,
                credentials.storePass,
                credentials.keyPass,
                credentials.type
            )
            if (flexible != null) {
                updatePrefs(
                    flexible.keyMaterial.alias,
                    flexible.storePass,
                    flexible.keyPass,
                    flexible.keyMaterial.storeType,
                    fingerprint
                )
                credentials = resolveCredentials()
                strictResult = StrictLoadResult(flexible.keyMaterial, flexible.storePass)
            }
        }

        if (strictResult == null) {
            val recovered = recoverManagerKeystoreCredentials(keystoreData)
            if (recovered == null) {
                if (notifyOnInvalidCredentials) notifyInvalidKeystoreCredentials()
                throw IllegalArgumentException("Invalid keystore credentials")
            }
            updatePrefs(
                recovered.keyMaterial.alias,
                recovered.storePass,
                recovered.keyPass,
                recovered.keyMaterial.storeType,
                fingerprint
            )
            credentials = resolveCredentials()
            strictResult = StrictLoadResult(recovered.keyMaterial, null)
        }

        val keyMaterial = strictResult.keyMaterial
        if (credentials.fingerprint.isNullOrBlank() || strictResult.storePassOverride != null) {
            val updatedStorePass = strictResult.storePassOverride ?: credentials.storePass
            updatePrefs(
                keyMaterial.alias,
                updatedStorePass,
                credentials.keyPass,
                keyMaterial.storeType,
                fingerprint
            )
        }

        return CachedSigningKey(
            SigningKeyCacheKey(fingerprint, resolveCredentials()),
            keyMaterial,
            InPlaceApkSigner(keyMaterial.alias, keyMaterial.privateKey, keyMaterial.certificates)
        ).also { cachedSigningKey = it }
    }

    private suspend fun signWithApksig(
        input: File,
        output: File,
        preserveSignatureMetadata: Boolean
    ) {
        val keyMaterial = signingKey().keyMaterial
        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            keyMaterial.alias,
            keyMaterial.privateKey,
            keyMaterial.certificates
        ).build()

        runInterruptible(Dispatchers.IO) {
            AndroidApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .supportSigningKey(keyMaterial.privateKey, input)
                .setV1SigningEnabled(!preserveSignatureMetadata)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(preserveSignatureMetadata)
                .setAlignmentPreserved(preserveSignatureMetadata)
                .build()
                .sign()
        }
    }

    private data class StrictLoadResult(
        val keyMaterial: KeyMaterial,
        val storePassOverride: String?
    )

    private data class RecoveredCredentials(
        val keyMaterial: KeyMaterial,
        val storePass: String,
        val keyPass: String,
    )

    private fun resolveSigningType(credentials: KeystoreCredentials): String? {
        val type = credentials.type?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (type != null) return type
        return if (credentials.alias == DEFAULT_ALIAS || credentials.alias == LEGACY_DEFAULT_ALIAS) "BKS" else null
    }

    private fun recoverManagerKeystoreCredentials(keystoreData: ByteArray): RecoveredCredentials? {
        val aliasCandidates = listOf(DEFAULT_ALIAS, LEGACY_DEFAULT_ALIAS, "ReVanced Key")
        val passCandidates = listOf(DEFAULT_PASSWORD, LEGACY_DEFAULT_ALIAS, LEGACY_DEFAULT_PASSWORD, "")
        val types = keyStoreTypes(null)

        for (type in types) {
            for (storePass in passCandidates) {
                val storeChars = storePass.takeIf { it.isNotEmpty() }?.toCharArray()
                val ks = loadKeyStoreFromBytes(keystoreData, type, storeChars, preferBc = true) ?: continue
                val aliasesInStore = ks.aliases().toList()
                if (aliasesInStore.isEmpty()) continue
                for (alias in aliasCandidates) {
                    val aliasSearch = if (aliasesInStore.contains(alias)) {
                        listOf(alias)
                    } else {
                        aliasesInStore
                    }
                    for (candidateAlias in aliasSearch) {
                        for (keyPass in passCandidates) {
                            val keyChars = keyPass.takeIf { it.isNotEmpty() }?.toCharArray()
                            val privateKey = runCatching {
                                ks.getKey(candidateAlias, keyChars) as? PrivateKey
                            }.getOrNull() ?: continue
                            val certs = ks.getCertificateChain(candidateAlias)
                                ?.mapNotNull { it as? X509Certificate }
                                ?.takeIf { it.isNotEmpty() }
                                ?: continue
                            val material = KeyMaterial(candidateAlias, privateKey, certs, type)
                            return RecoveredCredentials(material, storePass, keyPass)
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun notifyInvalidKeystoreCredentials() {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                appContext,
                "Keystore credentials are not correct. Re-import or regenerate the keystore.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun regenerateLocked() {
        Log.i(LOG_TAG, "Regenerating keystore")
        val keyCertPair = RevancedApkSigner.newPrivateKeyCertificatePair(
            DEFAULT_ALIAS,
            maxCertificateNotAfter
        )
        val ks = RevancedApkSigner.newKeyStore(
            setOf(
                RevancedApkSigner.KeyStoreEntry(
                    DEFAULT_ALIAS, DEFAULT_KEY_PASSWORD, keyCertPair
                )
            )
        )
        val bytes = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { output ->
                ks.store(output, DEFAULT_PASSWORD.toCharArray())
                output.toByteArray()
            }
        }
        val fingerprint = keystoreFingerprint(bytes)
        withContext(Dispatchers.IO) {
            Files.write(keystorePath.toPath(), bytes)
            writeBackupKeystore(bytes)
            deleteLegacyKeystoreCopies()
        }

        updatePrefs(DEFAULT_ALIAS, DEFAULT_PASSWORD, DEFAULT_KEY_PASSWORD, "BKS", fingerprint)
        Log.i(LOG_TAG, "Keystore regenerated at ${keystorePath.absolutePath}")
    }

    private suspend fun writeBackupKeystore(bytes: ByteArray) {
        val backupPath = backupKeystorePath ?: return
        withContext(Dispatchers.IO) {
            backupPath.parentFile?.mkdirs()
            Files.write(backupPath.toPath(), bytes)
            deleteLegacyKeystoreCopies()
        }
        Log.d(LOG_TAG, "Keystore backup saved to ${backupPath.absolutePath}")
    }

    private suspend fun tryRestoreKeystore(): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(backupKeystorePath) + legacyRestoreCandidates()
        val restored = recoverManagerKeystore(
            candidates = candidates,
            destination = keystorePath,
            validateCredentials = ::rehydrateCredentials,
            persistBackup = ::writeBackupKeystore
        )
        if (restored) {
            deleteLegacyKeystoreCopies()
            Log.i(LOG_TAG, "Keystore restored after validating its signing key and credentials")
        } else {
            Log.w(LOG_TAG, "No keystore backup candidates found")
        }
        restored
    }

    private fun legacyRestoreCandidates(): List<File> = legacyManagerKeystoreFiles(
        signingDirectory = keystorePath.parentFile!!,
        filesDirectory = appContext.filesDir,
        backupDirectory = backupKeystorePath?.parentFile
    )

    private fun deleteLegacyKeystoreCopies() {
        legacyRestoreCandidates().forEach { legacyFile ->
            runCatching {
                if (legacyFile.exists()) {
                    Files.deleteIfExists(legacyFile.toPath())
                }
            }.onFailure {
                Log.w(LOG_TAG, "Failed to delete legacy keystore copy at ${legacyFile.absolutePath}", it)
            }
        }
    }

    private suspend fun rehydrateCredentials(keystoreData: ByteArray): Boolean {
        val fileCredentials = try {
            readCredentials()
        } catch (error: IOException) {
            Log.w(LOG_TAG, "Unable to read saved keystore credentials; trying preferences", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.w(LOG_TAG, "Invalid saved keystore credentials; trying preferences", error)
            null
        }
        val aliasCandidates = listOfNotNull(
            fileCredentials?.alias,
            prefs.keystoreAlias.get(),
            DEFAULT_ALIAS,
            LEGACY_DEFAULT_ALIAS,
            "ReVanced Key"
        ).filter { it.isNotBlank() }.distinct()
        val passCandidates = listOfNotNull(
            fileCredentials?.storePass,
            fileCredentials?.keyPass,
            prefs.keystorePass.get(),
            prefs.keystoreKeyPass.get(),
            DEFAULT_PASSWORD,
            LEGACY_DEFAULT_ALIAS,
            LEGACY_DEFAULT_PASSWORD
        ).filter { it.isNotBlank() }.distinct()
        val fingerprint = keystoreFingerprint(keystoreData)
        val aliasSearch = (aliasCandidates + "").distinct()

        for (alias in aliasSearch) {
            for (storePass in passCandidates) {
                for (keyPass in passCandidates) {
                    val loaded = tryLoadKeyMaterial(
                        keystoreData,
                        alias,
                        storePass,
                        keyPass
                    ) ?: continue
                    val resolvedStorePass = loaded.storePass.ifBlank { loaded.keyPass }.trim()
                    val resolvedKeyPass = loaded.keyPass.ifBlank { loaded.storePass }.trim()
                    if (resolvedStorePass.isBlank() || resolvedKeyPass.isBlank()) continue
                    val material = loaded.keyMaterial
                    val persistedType = if (material.storeType == "JKS") "PKCS12" else material.storeType
                    updatePrefs(material.alias, resolvedStorePass, resolvedKeyPass, persistedType, fingerprint)
                    Log.i(
                        LOG_TAG,
                        "Rehydrated keystore credentials (alias=${material.alias}, type=$persistedType)"
                    )
                    return true
                }
            }
        }
        return false
    }

    private fun loadKeyMaterialStrict(
        keystoreData: ByteArray,
        alias: String,
        storePass: String,
        keyPass: String,
        type: String,
        allowNullStorePassFallback: Boolean
    ): StrictLoadResult? {
        val trimmedAlias = alias.trim()
        if (trimmedAlias.isEmpty()) return null
        fun loadWithStorePass(storePassChars: CharArray?): KeyMaterial? {
            val ks = loadKeyStoreFromBytes(keystoreData, type, storePassChars, preferBc = true)
                ?: return null
            val keyCandidates = buildList<CharArray?> {
                if (keyPass.isNotBlank()) add(keyPass.toCharArray())
                if (storePass.isNotBlank() && storePass != keyPass) add(storePass.toCharArray())
                add(null)
            }.distinctBy { it?.concatToString() }
            val privateKey = keyCandidates.firstNotNullOfOrNull { candidate ->
                runCatching { ks.getKey(trimmedAlias, candidate) as? PrivateKey }.getOrNull()
            } ?: return null
            val certs = ks.getCertificateChain(trimmedAlias)
                ?.mapNotNull { it as? X509Certificate }
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return KeyMaterial(trimmedAlias, privateKey, certs, type)
        }

        val primary = runCatching {
            loadWithStorePass(storePass.takeIf { it.isNotBlank() }?.toCharArray())
        }.getOrNull()
        if (primary != null) return StrictLoadResult(primary, null)
        if (!allowNullStorePassFallback || storePass.isBlank()) return null
        val fallback = runCatching { loadWithStorePass(null) }.getOrNull()
        return fallback?.let { StrictLoadResult(it, "") }
    }

    private fun tryLoadJksKeyMaterial(
        keystoreData: ByteArray,
        alias: String,
        storePass: String,
        keyPass: String
    ): LoadedKeyMaterial? {
        val entries = readJksEntries(keystoreData) ?: return null
        if (entries.isEmpty()) return null
        val entryCandidates = when {
            alias.isNotBlank() -> entries.filter { it.alias.equals(alias, ignoreCase = true) }
                .ifEmpty { entries }
            else -> entries
        }

        val keyCandidates = buildList<CharArray?> {
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }

        for (entry in entryCandidates) {
            val protectedCandidates = protectedKeyCandidates(entry.protectedKey)
            var usedKeyPass = ""
            val keyBytes = keyCandidates.firstNotNullOfOrNull { candidate ->
                protectedCandidates.firstNotNullOfOrNull { protectedKey ->
                    unprotectJksKey(protectedKey, candidate)
                }?.also {
                    usedKeyPass = candidate?.concatToString().orEmpty()
                }
            } ?: continue
            val algorithm = entry.certificates.firstOrNull()?.publicKey?.algorithm ?: continue
            val privateKey = KeyFactory.getInstance(algorithm)
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val storePassUsed = storePass.ifBlank { usedKeyPass }
            return LoadedKeyMaterial(
                KeyMaterial(entry.alias, privateKey, entry.certificates, "JKS"),
                storePassUsed,
                usedKeyPass
            )
        }
        return null
    }

    private data class JksEntry(
        val alias: String,
        val protectedKey: ByteArray,
        val certificates: List<X509Certificate>
    )

    private fun readJksEntries(data: ByteArray): List<JksEntry>? {
        val input = DataInputStream(ByteArrayInputStream(data))
        val magic = input.readInt()
        if (magic != 0xFEEDFEED.toInt()) return null
        val version = input.readInt()
        if (version != 1 && version != 2) return null
        val entryCount = input.readInt()
        val certFactoryCache = HashMap<String, CertificateFactory>()
        val entries = mutableListOf<JksEntry>()

        repeat(entryCount) {
            when (input.readInt()) {
                1 -> {
                    val alias = input.readUTF()
                    input.readLong()
                    val keyLen = input.readInt()
                    val protectedKey = ByteArray(keyLen).also { input.readFully(it) }
                    val chainLen = input.readInt()
                    val chain = ArrayList<X509Certificate>(chainLen)
                    repeat(chainLen) {
                        val certType = input.readUTF()
                        val certLen = input.readInt()
                        val certBytes = ByteArray(certLen).also { input.readFully(it) }
                        val factory = certFactoryCache.getOrPut(certType) {
                            CertificateFactory.getInstance(certType)
                        }
                        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                        chain.add(cert)
                    }
                    entries.add(JksEntry(alias, protectedKey, chain))
                }
                2 -> {
                    input.readUTF()
                    input.readLong()
                    val certType = input.readUTF()
                    val certLen = input.readInt()
                    input.skipBytes(certLen)
                }
                3 -> {
                    input.readUTF()
                    input.readLong()
                    val keyLen = input.readInt()
                    input.skipBytes(keyLen)
                }
                else -> return null
            }
        }
        return entries
    }

    private fun unprotectJksKey(protectedKey: ByteArray, password: CharArray?): ByteArray? {
        if (protectedKey.size < 40) return null
        val salt = protectedKey.copyOfRange(0, 20)
        val encrypted = protectedKey.copyOfRange(20, protectedKey.size - 20)
        val checksum = protectedKey.copyOfRange(protectedKey.size - 20, protectedKey.size)
        val passwordBytes = jksPasswordBytes(password)
        val md = MessageDigest.getInstance("SHA-1")
        var digest = md.digest(passwordBytes + salt)
        val plain = ByteArray(encrypted.size)
        var offset = 0
        while (offset < encrypted.size) {
            for (i in digest.indices) {
                if (offset >= encrypted.size) break
                plain[offset] = (encrypted[offset].toInt() xor digest[i].toInt()).toByte()
                offset++
            }
            md.reset()
            md.update(passwordBytes)
            md.update(digest)
            digest = md.digest()
        }
        md.reset()
        md.update(passwordBytes)
        md.update(plain)
        val computed = md.digest()
        return if (computed.contentEquals(checksum)) plain else null
    }

    private fun jksPasswordBytes(password: CharArray?): ByteArray {
        if (password == null) return ByteArray(0)
        val bytes = ByteArray(password.size * 2)
        var idx = 0
        for (ch in password) {
            bytes[idx++] = (ch.code shr 8).toByte()
            bytes[idx++] = ch.code.toByte()
        }
        return bytes
    }

    private fun keystoreFingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            hex.append(String.format("%02x", byte))
        }
        return hex.toString()
    }

    private fun protectedKeyCandidates(raw: ByteArray): List<ByteArray> {
        val extracted = extractEncryptedPrivateKeyData(raw)
        return if (extracted == null || extracted.contentEquals(raw)) {
            listOf(raw)
        } else {
            listOf(extracted, raw)
        }
    }

    private fun extractEncryptedPrivateKeyData(raw: ByteArray): ByteArray? {
        return runCatching {
            val info = EncryptedPrivateKeyInfo(raw)
            info.encryptedData.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun buildPkcs12Keystore(
        alias: String,
        keyPass: String,
        storePass: String,
        privateKey: PrivateKey,
        certificates: List<X509Certificate>
    ): ByteArray {
        val pkcs12 = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                alias,
                privateKey,
                keyPass.toCharArray(),
                certificates.toTypedArray()
            )
        }
        val output = ByteArrayOutputStream()
        pkcs12.store(output, storePass.toCharArray())
        return output.toByteArray()
    }

    private fun buildKeystoreBytes(
        alias: String,
        keyPass: String,
        storePass: String,
        privateKey: PrivateKey,
        certificates: List<X509Certificate>,
        storeType: String
    ): ByteArray {
        val outputStoreType = resolveWritableStoreType(storeType)
        val ks = keyStoreInstance(outputStoreType, preferBc = true).apply {
            load(null, null)
            setKeyEntry(
                alias,
                privateKey,
                keyPass.toCharArray(),
                certificates.toTypedArray()
            )
        }
        return ByteArrayOutputStream().use { output ->
            ks.store(output, storePass.toCharArray())
            output.toByteArray()
        }
    }

    private fun resolveWritableStoreType(requestedType: String): String {
        val normalized = requestedType.trim().uppercase().ifBlank { "PKCS12" }
        val candidates = when (normalized) {
            "BKS" -> listOf("BKS", "BKS-V1", "PKCS12")
            "JKS" -> listOf("JKS", "PKCS12")
            "PKCS12" -> listOf("PKCS12")
            else -> listOf(normalized, "PKCS12")
        }.distinct()

        return candidates.firstOrNull { candidate ->
            runCatching { keyStoreInstance(candidate, preferBc = true) }.isSuccess
        } ?: "PKCS12"
    }
}
