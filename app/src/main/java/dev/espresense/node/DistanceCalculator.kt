package dev.espresense.node

import kotlin.math.pow

/**
 * Log-distance path-loss model, matching the formula used by ESPresense's
 * ESP32 firmware and ESPresense-Pi:
 *
 *   distance = 10 ^ ((refRssi - (rssi - rxAdjRssi)) / (10 * absorption))
 *
 * `refRssi` is the calibrated RSSI measured at 1 meter (either the fixed
 * "ref_rssi" setting for generic devices, or the iBeacon's own broadcast
 * "measured power" byte). `absorption` is the environmental path-loss
 * exponent (ESPresense default 2.7). `rxAdjRssi` compensates for this
 * particular receiver's gain, since `refRssi` is a property of the
 * transmitter and is therefore shared by every node.
 *
 * Note the sign: ESPresense *subtracts* rx_adj_rssi, so a node that hears
 * everything too loudly needs a positive value. Verified against the ESP32
 * firmware, where flipping rx_adj_rssi 0 -> 34 moved the reported RSSI from
 * -48.3 to -82.1 dBm.
 */
object DistanceCalculator {
    fun estimate(rssi: Int, refRssi: Int, absorption: Float, rxAdjRssi: Int = 0): Double {
        if (rssi == 0) return -1.0
        val ratio = (refRssi - (rssi - rxAdjRssi)) / (10.0 * absorption)
        return 10.0.pow(ratio)
    }
}
