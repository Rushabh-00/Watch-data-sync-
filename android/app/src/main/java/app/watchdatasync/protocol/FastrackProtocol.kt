package app.watchdatasync.protocol

/**
 * Placeholder for a model-specific Fastrack adapter.
 *
 * Do not add guessed UUIDs or guessed packet formats here.
 * The implementation should be backed by captured GATT evidence for a
 * specific compatible watch model.
 */
class FastrackProtocol(
    override val displayName: String,
    private val acceptedNames: Set<String>,
    private val acceptedServices: Set<String>,
) : WatchProtocol {
    override val id: String = "fastrack"

    override fun matches(
        advertisedName: String?,
        serviceUuids: Set<String>,
    ): Boolean {
        val nameMatches = advertisedName?.let { value ->
            acceptedNames.any { value.contains(it, ignoreCase = true) }
        } == true

        val serviceMatches = serviceUuids.any {
            it.lowercase() in acceptedServices.map(String::lowercase)
        }
        return nameMatches || serviceMatches
    }

    override fun describe(packet: ByteArray): String =
        "Fastrack candidate packet: " + packet.size + " bytes"
}
