package dev.espresense.node

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves Bluetooth LE "Resolvable Private Addresses" (RPAs) against known Identity
 * Resolving Keys (IRKs), the same mechanism ESPresense firmware nodes use to recognize
 * enrolled Apple/Android devices even though their advertised BLE MAC rotates every
 * ~15 minutes (see espresense.com/guides/enrolling-devices).
 *
 * A RPA is composed of prand (most significant 3 octets, as normally displayed) || hash
 * (least significant 3 octets). A device knowing the IRK can recompute hash' = ah(IRK,
 * prand) and check it against the advertised hash; a match proves the address was
 * generated from that IRK.
 *
 * ah() is defined in the Bluetooth Core Spec (Cryptographic Toolbox) as:
 *   r' = 13 zero octets || prand (24 bits), AES-128-ECB encrypt with IRK as key,
 *   hash = the least significant 3 octets of the result.
 *
 * Both the IRK and the address are stored/transmitted by the Bluetooth stack in
 * "over-the-air" octet order (least-significant octet first), which is the OPPOSITE of
 * how the AES cipher and Core Spec's `e()` function expect their 128-bit inputs (most
 * significant octet first). Reference host stacks (Zephyr's subsys/bluetooth/common/rpa.c,
 * BlueZ's src/shared/crypto.c bt_crypto_e/bt_crypto_ah) both byte-swap the key and
 * plaintext before the raw AES call, and byte-swap the result back afterwards - this
 * implementation mirrors that exactly.
 */
object IrkResolver {
    private const val TAG = "IrkResolver"

    // Verified against BlueZ's unit test test_sih() (unit/test-crypto.c), which forwards
    // directly to bt_crypto_ah() - the same "ah" function used for RPA resolution.
    private const val TEST_IRK = "cdcc72dd868ccdce22fda121097d7d45"
    private val TEST_PRAND = byteArrayOf(0x63, 0xf5.toByte(), 0x69)
    private val TEST_HASH = byteArrayOf(0xda.toByte(), 0x48, 0x19)

    /** True once [selfTest] has run and confirmed our ah() implementation matches the verified test vector. */
    val verified: Boolean by lazy { selfTest() }

    private fun selfTest(): Boolean {
        return try {
            val irkBytes = hexToBytes(TEST_IRK)
            val result = ah(irkBytes, TEST_PRAND)
            val ok = result.contentEquals(TEST_HASH)
            if (!ok) {
                Log.w(TAG, "ah() self-test FAILED (got ${result.joinToString("") { "%02x".format(it) }}); IRK resolution disabled")
            } else {
                Log.i(TAG, "ah() self-test passed; IRK resolution enabled")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "ah() self-test error; IRK resolution disabled", e)
            false
        }
    }

    /** Standard AES-128-ECB single block encrypt, key/plaintext/output all MSB-first ("e" function). */
    private fun e(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plaintext)
    }

    /** ah(k, r) per Core Spec, with the LE<->BE octet-order conversion used by real host stacks. */
    private fun ah(irkLe: ByteArray, prandLe: ByteArray): ByteArray {
        val blockLe = ByteArray(16)
        prandLe.copyInto(blockLe, 0)
        val keyBe = irkLe.reversedArray()
        val plaintextBe = blockLe.reversedArray()
        val outBe = e(keyBe, plaintextBe)
        val outLe = outBe.reversedArray()
        return outLe.copyOfRange(0, 3)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private fun macToBytes(mac: String): ByteArray? {
        val parts = mac.split(":")
        if (parts.size != 6) return null
        return try {
            ByteArray(6) { i -> parts[i].toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Checks [mac] against every "irk:<32-hex>" fingerprint in [irkFingerprints].
     * Returns the matching fingerprint (e.g. "irk:798f4b24...") if resolved, else null.
     */
    fun tryResolve(mac: String, irkFingerprints: Collection<String>): String? {
        if (!verified) return null
        // mac is displayed MSB-group-first ("AA:BB:CC:DD:EE:FF"); the stack's native octet
        // order is the reverse of that. prand occupies the top 3 (most significant) octets
        // and hash the bottom 3, both in native (reversed-display) order.
        val display = macToBytes(mac) ?: return null
        val prand = byteArrayOf(display[2], display[1], display[0])
        val expectedHash = byteArrayOf(display[5], display[4], display[3])

        // DEBUG: only resolvable-private-addresses have their top 2 bits (of the first
        // displayed octet) set to 0b01 - log near-misses for those so we can diagnose
        // byte-order issues without guessing blindly.
        val isRpa = (display[0].toInt() and 0xC0) == 0x40
        if (isRpa) {
            val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
            Log.d(TAG, "RPA candidate $mac prand=${hex(prand)} expectedHash=${hex(expectedHash)}")
        }

        for (fingerprint in irkFingerprints) {
            if (!fingerprint.startsWith("irk:")) continue
            val hex = fingerprint.substring(4)
            if (hex.length != 32) continue
            try {
                // ESPresense firmware (main/BleFingerprint.cpp ble_ll_resolv_rpa) uses the
                // published "irk:<hex>" bytes DIRECTLY as the raw AES key (no byte-swap) -
                // i.e. those bytes are already in our internal ah()'s "key" (BE) form. Our
                // ah() expects an LE-style irk and reverses it internally, so we must feed
                // it the reverse of the fingerprint bytes to land on the same AES key.
                val irkBytes = hexToBytes(hex).reversedArray()
                val computed = ah(irkBytes, prand)
                if (isRpa) {
                    Log.d(TAG, "  vs $fingerprint -> computed=${computed.joinToString("") { "%02x".format(it) }}")
                }
                if (computed.contentEquals(expectedHash)) return fingerprint
            } catch (e: Exception) {
                // malformed hex from a bad retained config; skip it
            }
        }
        return null
    }
}

