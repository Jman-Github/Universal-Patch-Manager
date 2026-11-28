package app.universal.patch.manager.data.room.profile

import app.universal.patch.manager.data.room.options.Option
import kotlinx.serialization.Serializable

@Serializable
data class PatchProfilePayload(
    val bundles: List<Bundle>,
    val signatureWorkflow: SignatureWorkflow = SignatureWorkflow()
) {
    @Serializable
    data class SignatureWorkflow(
        val enabled: Boolean = false,
        val remembered: Boolean = false,
        val injected: Boolean = false
    )

    @Serializable
    data class OptionDisplayInfo(
        val label: String? = null,
        val displayValue: String? = null
    )

    @Serializable
    data class PatchDisplayInfo(val label: String, val description: String? = null)

    @Serializable
    data class Bundle(
        val bundleUid: Int,
        val patches: List<String>,
        val options: Map<String, Map<String, Option.SerializedValue>>,
        val displayName: String? = null,
        val sourceEndpoint: String? = null,
        val sourceName: String? = null,
        val version: String? = null,
        val optionDisplayInfo: Map<String, Map<String, OptionDisplayInfo>>? = null,
        val patchDisplayInfo: Map<String, PatchDisplayInfo>? = null
    )
}
