package lab.luxi.tingmo.domain

/**
 * 拾音场景：课堂录音需要更高增益与更宽拾音；近讲则压增益防噪声。
 * [sensitivity] 0–100，对应滑杆；场景名为就近档位标签。
 */
data class PickupProfile(
    val sensitivity: Int,
    val label: String,
    val hint: String,
    val maxGain: Double,
    val targetPeak: Int,
    val floorGain: Double,
    val minPeakForGain: Int,
    val energyThreshold: Int,
) {
    companion object {
        const val DEFAULT_SENSITIVITY = 72 // 默认偏「课堂」

        fun fromSensitivity(raw: Int): PickupProfile {
            val s = raw.coerceIn(0, 100)
            return when {
                s <= 25 -> PickupProfile(
                    sensitivity = s,
                    label = "近讲",
                    hint = "贴麦 / 口述，增益低、噪声少",
                    maxGain = 3.0,
                    targetPeak = 16_000,
                    floorGain = 1.2,
                    minPeakForGain = 120,
                    energyThreshold = 45,
                )
                s <= 50 -> PickupProfile(
                    sensitivity = s,
                    label = "会议",
                    hint = "桌面距离发言，中等增益",
                    maxGain = 6.0,
                    targetPeak = 20_000,
                    floorGain = 2.5,
                    minPeakForGain = 80,
                    energyThreshold = 28,
                )
                s <= 78 -> PickupProfile(
                    sensitivity = s,
                    label = "课堂",
                    hint = "教室远讲 / 走动授课，推荐默认",
                    maxGain = 11.0 + (s - 50) * 0.12, // 50→11, 78→~14.3
                    targetPeak = 23_000,
                    floorGain = 5.0 + (s - 50) * 0.08,
                    minPeakForGain = 50,
                    energyThreshold = 18,
                )
                else -> PickupProfile(
                    sensitivity = s,
                    label = "远场",
                    hint = "后排 / 空旷教室，高增益（噪声也可能变大）",
                    maxGain = 16.0,
                    targetPeak = 25_000,
                    floorGain = 8.0,
                    minPeakForGain = 35,
                    energyThreshold = 12,
                )
            }
        }

        /** 滑杆刻度旁的场景锚点 */
        val anchors = listOf(
            12 to "近讲",
            38 to "会议",
            72 to "课堂",
            92 to "远场",
        )
    }
}
