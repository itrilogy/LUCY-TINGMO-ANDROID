package lab.luxi.tingmo.asr

import lab.luxi.tingmo.domain.PickupProfile
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 软件自动增益：参数由 [PickupProfile]（课堂/会议等场景滑杆）驱动。
 */
object AudioGain {
    private val profileRef = AtomicReference(
        PickupProfile.fromSensitivity(PickupProfile.DEFAULT_SENSITIVITY),
    )

    fun currentProfile(): PickupProfile = profileRef.get()

    fun applyProfile(profile: PickupProfile) {
        profileRef.set(profile)
    }

    fun applySensitivity(sensitivity: Int) {
        profileRef.set(PickupProfile.fromSensitivity(sensitivity))
    }

    fun boost(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val p = currentProfile()
        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak < p.minPeakForGain) {
            // 极弱信号：仍给 floorGain，利于课堂远讲
            return applyFixedGain(samples, p.floorGain)
        }
        if (peak >= p.targetPeak) return samples
        val gain = min(p.maxGain, p.targetPeak.toDouble() / peak.toDouble())
        if (gain <= 1.05) return samples
        return applyFixedGain(samples, gain)
    }

    fun boostChunk(samples: ShortArray, recentPeak: Int): Pair<ShortArray, Int> {
        if (samples.isEmpty()) return samples to recentPeak
        val p = currentProfile()
        var peak = recentPeak
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        peak = max((peak * 92) / 100, peak) // 略快衰减，适应走动远近
        val gain = if (peak < p.minPeakForGain) {
            p.floorGain
        } else {
            min(p.maxGain, p.targetPeak.toDouble() / max(peak, 1).toDouble())
        }
        return applyFixedGain(samples, gain) to peak
    }

    fun energyThreshold(): Int = currentProfile().energyThreshold

    private fun applyFixedGain(samples: ShortArray, gain: Double): ShortArray {
        if (gain <= 1.02) return samples
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i] * gain).toInt()
            out[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toShort()
            }
        }
        return out
    }
}
