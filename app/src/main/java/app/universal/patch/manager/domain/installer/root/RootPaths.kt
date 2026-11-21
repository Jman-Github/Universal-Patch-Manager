package app.universal.patch.manager.domain.installer.root

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

internal object RootPaths {
    const val ROOT = "/data/adb/urv"
    const val MODULES = "/data/adb/modules"
    const val LEGACY = "/data/adb/revanced"
    const val LEGACY_SERVICE = "/data/adb/service.d"

    fun lock(packageName: String) = "$ROOT/locks/$packageName.lock.d"
    fun lockOwner(packageName: String) = "${lock(packageName)}/owner"
    fun transaction(packageName: String) = "$ROOT/transactions/$packageName"
    fun active(packageName: String) = "${transaction(packageName)}/active.json"
    fun committed(packageName: String) = "${transaction(packageName)}/committed.json"
    fun diagnostics(packageName: String) = "${transaction(packageName)}/diagnostics.log"
    fun backup(packageName: String) = "${transaction(packageName)}/backup"
    fun canonicalPayload(packageName: String) = "${backup(packageName)}/payload"
    fun canonicalPatched(packageName: String) = "${canonicalPayload(packageName)}/patched"
    fun canonicalStock(packageName: String) = "${canonicalPayload(packageName)}/stock"
    fun legacyPackage(packageName: String) = "$LEGACY/$packageName"
    fun legacyService(packageName: String) = "$LEGACY_SERVICE/urv-$packageName.sh"
    fun competingService(packageName: String) = "$LEGACY_SERVICE/$packageName.sh"
    fun competingModule(packageName: String) = "$MODULES/$packageName-ReVanced"
    fun competingPayloads(packageName: String) = listOf(
        "${legacyPackage(packageName)}/$packageName.apk",
        "${competingModule(packageName)}/$packageName.apk"
    )
    fun module(packageName: String) = "$MODULES/$packageName-revanced"
    fun moduleApk(packageName: String) = "${module(packageName)}/$packageName.apk"
    fun moduleStockApk(packageName: String) = "${module(packageName)}/$packageName-stock.apk"
    fun rollbackModule(packageName: String) = "$MODULES/.$packageName-revanced.urv-rollback"
    fun stagingModule(packageName: String, transactionId: String) =
        "$MODULES/.$packageName-revanced.urv-stage-$transactionId"
}
