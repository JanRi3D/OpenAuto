package com.shilapi.xcertplay.airplay

/** Store of paired controllers keyed by their long-term Ed25519 public key. */
class PairingStore(private val onSave: ((String, ByteArray) -> Unit)? = null) {
    private val entries = HashMap<String, ByteArray>()

    @Synchronized fun save(identifier: String, longTermPublicKey: ByteArray) {
        require(identifier.isNotEmpty() && identifier.toByteArray(Charsets.UTF_8).size <= 256) { "Invalid pairing identifier" }
        require(longTermPublicKey.size == 32) { "Invalid Ed25519 public key" }
        require(entries.containsKey(identifier) || entries.size < 32) { "Pairing store full" }
        val copy = longTermPublicKey.copyOf()
        onSave?.invoke(identifier, copy.copyOf())
        entries[identifier] = copy
    }

    @Synchronized fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    @Synchronized fun clear() = entries.clear()
}
