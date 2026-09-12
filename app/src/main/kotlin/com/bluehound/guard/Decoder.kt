package com.bluehound.guard

import android.bluetooth.le.ScanRecord

data class Decoded(
    val icon: String?,
    val type: String?,
    val maker: String?,
    val trackerLabel: String?,
    val isTracker: Boolean
)

/**
 * Turns a raw Bluetooth advertisement into a plain-English guess:
 * who makes the device, what kind of device it probably is, and
 * whether it matches a known item-tracker signature.
 */
object Decoder {

    // Bluetooth SIG company IDs -> maker name (common ones)
    private val COMPANY = mapOf(
        0x0006 to "Microsoft", 0x000F to "Broadcom", 0x0002 to "Intel", 0x004C to "Apple",
        0x0075 to "Samsung", 0x00E0 to "Google", 0x0087 to "Garmin", 0x00D2 to "Dialog/Renesas",
        0x0157 to "Xiaomi/Huami", 0x038F to "Xiaomi", 0x027D to "Huawei", 0x0171 to "Amazon",
        0x00C4 to "LG", 0x012D to "Sony", 0x009E to "Bose", 0x0057 to "Harman (JBL)",
        0x02FF to "Sonos", 0x0059 to "Nordic Semi", 0x0499 to "Ruuvi", 0x0154 to "Fitbit",
        0x006B to "Polar", 0x0131 to "Cypress", 0x000A to "Qualcomm/CSR", 0x000D to "TI",
        0x05A7 to "Sonova", 0x01D7 to "Anker", 0x038E to "OnePlus", 0x0810 to "Govee",
        0x0001 to "Ericsson", 0x004F to "APT/Qualcomm", 0x00E4 to "Logitech", 0x0397 to "Tile",
        0x03EE to "Chipolo", 0x0092 to "GoPro", 0x0100 to "TomTom", 0x018D to "Bang & Olufsen"
    )

    // 16-bit service UUIDs -> what kind of device it probably is
    private val SERVICE_TYPE = mapOf(
        "180d" to Pair("⌚", "Heart-rate sensor / fitness band"),
        "1814" to Pair("👟", "Running sensor"),
        "1816" to Pair("🚴", "Bike sensor"),
        "1818" to Pair("🚴", "Bike power meter"),
        "1826" to Pair("🏋️", "Exercise machine"),
        "1812" to Pair("🖱️", "Keyboard / mouse / remote"),
        "1108" to Pair("🎧", "Headphones"),
        "110b" to Pair("🔊", "Audio device"),
        "fe2c" to Pair("🎧", "Earbuds / Fast Pair accessory"),
        "fd6f" to Pair("📱", "Phone (exposure beacon)"),
        "fe9f" to Pair("📱", "Google device"),
        "fef3" to Pair("📱", "Google device"),
        "fdee" to Pair("📺", "Chromecast / cast device"),
        "fea0" to Pair("📺", "Cast device"),
        "1802" to Pair("🏷️", "Find-me tag"),
        "1803" to Pair("🏷️", "Proximity tag"),
        "181c" to Pair("⌚", "Wearable"),
        "180a" to Pair("🔧", "Generic gadget")
    )

    // service UUIDs broadcast by known item trackers
    private val TRACKER_SERVICE = mapOf(
        "feed" to "Tile",
        "feec" to "Tile",
        "fd5a" to "Samsung SmartTag",
        "fe33" to "Chipolo"
    )

    // company IDs that are trackers outright (Apple handled by payload check)
    private val TRACKER_COMPANY = mapOf(
        0x0397 to "Tile",
        0x03EE to "Chipolo"
    )

    private val NAME_TYPE = listOf(
        Regex("bud|airpod|earph|headph|headset|wh-|wf-|jbl|beats|soundcore", RegexOption.IGNORE_CASE) to Pair("🎧", "Headphones / earbuds"),
        Regex("watch|band|fit|amazfit|garmin|polar|versa|charge \\d", RegexOption.IGNORE_CASE) to Pair("⌚", "Watch / fitness band"),
        Regex("tv|bravia|roku|fire ?tv|chromecast|shield", RegexOption.IGNORE_CASE) to Pair("📺", "TV / streamer"),
        Regex("speaker|boom|flip|soundbar|sonos|echo|home|nest", RegexOption.IGNORE_CASE) to Pair("🔊", "Speaker"),
        Regex("mouse|keyboard|keys|mx ", RegexOption.IGNORE_CASE) to Pair("🖱️", "Keyboard / mouse"),
        Regex("iphone|pixel|galaxy s|galaxy a|oneplus|phone", RegexOption.IGNORE_CASE) to Pair("📱", "Phone"),
        Regex("macbook|laptop|thinkpad|desktop", RegexOption.IGNORE_CASE) to Pair("💻", "Computer"),
        Regex("tile|airtag|smarttag|chipolo|tracker", RegexOption.IGNORE_CASE) to Pair("🏷️", "Tracker tag"),
        Regex("printer|deskjet|envy|brother|epson", RegexOption.IGNORE_CASE) to Pair("🖨️", "Printer"),
        Regex("car|sync|uconnect|carplay|toyota|ford|chevy|gmc", RegexOption.IGNORE_CASE) to Pair("🚗", "Vehicle"),
        Regex("scale|thermo|sensor|meat|probe|govee", RegexOption.IGNORE_CASE) to Pair("🌡️", "Sensor / smart home")
    )

    /** "0000fe2c-0000-1000-8000-00805f9b34fb" -> "fe2c", else null */
    private fun short16(uuid: String): String? {
        val m = Regex("^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$")
            .find(uuid.lowercase()) ?: return null
        return m.groupValues[1]
    }

    fun decode(record: ScanRecord?, name: String?): Decoded {
        var icon: String? = null
        var type: String? = null
        var maker: String? = null
        var trackerLabel: String? = null
        var isTracker = false

        if (record != null) {
            val mfr = record.manufacturerSpecificData
            if (mfr != null) {
                for (i in 0 until mfr.size()) {
                    val id = mfr.keyAt(i)
                    val data = mfr.valueAt(i)
                    if (maker == null) maker = COMPANY[id]
                    if (id == 0x004C && data != null && data.isNotEmpty() && data[0] == 0x12.toByte()) {
                        // Apple "Find My" offline-finding broadcast (AirTag or Find My accessory
                        // away from its owner) — NOT ordinary iPhones/Macs/AirPods
                        trackerLabel = "Apple Find My network"
                        isTracker = true
                    }
                    TRACKER_COMPANY[id]?.let { trackerLabel = it; isTracker = true }
                }
            }
            val uuids = mutableListOf<String>()
            record.serviceUuids?.forEach { uuids.add(it.uuid.toString()) }
            record.serviceData?.keys?.forEach { uuids.add(it.uuid.toString()) }
            for (u in uuids) {
                val s = short16(u) ?: continue
                if (type == null) SERVICE_TYPE[s]?.let { icon = it.first; type = it.second }
                TRACKER_SERVICE[s]?.let { trackerLabel = it; isTracker = true }
            }
        }

        if (type == null && !name.isNullOrBlank()) {
            for ((re, v) in NAME_TYPE) {
                if (re.containsMatchIn(name)) { icon = v.first; type = v.second; break }
            }
        }
        if (isTracker && type == null) { icon = "🏷️"; type = "Tracker tag" }
        if (type == null) when (maker) {
            "Apple" -> { icon = "📱"; type = "Apple device" }
            "Microsoft" -> { icon = "💻"; type = "Windows device" }
            "Samsung" -> { icon = "📱"; type = "Samsung device" }
        }
        return Decoded(icon, type, maker, trackerLabel, isTracker)
    }

    /** rough distance text from signal strength */
    fun distanceLabel(rssi: Int): String {
        val meters = Math.pow(10.0, (-59.0 - rssi) / 22.0)
        val feet = meters * 3.28
        return when {
            feet < 3 -> "under 3 ft"
            feet < 10 -> "~${feet.toInt()} ft"
            feet < 50 -> "~${(feet / 5).toInt() * 5} ft"
            else -> "far (50+ ft)"
        }
    }
}
