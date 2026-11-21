package com.example.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

enum class EqPreset(
    val label: String,
    /** Normalized -1f..1f levels across 5 standard bands (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz). */
    val normalizedBands: List<Float>,
    val defaultBassStrength: Int,
    val defaultLoudnessMb: Int
) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f), 0, 0),
    BASS_BOOST("Bass Boost", listOf(0.8f, 0.55f, 0.1f, -0.05f, 0.15f), 650, 250),
    VOCAL("Vocal Boost", listOf(-0.25f, 0.05f, 0.6f, 0.7f, 0.25f), 100, 350),
    CINEMA("Cinema", listOf(0.6f, 0.25f, 0.1f, 0.45f, 0.65f), 450, 450),
    ROCK("Rock", listOf(0.55f, 0.35f, -0.1f, 0.4f, 0.6f), 400, 200),
    ELECTRONIC("Electronic", listOf(0.7f, 0.45f, -0.2f, 0.35f, 0.75f), 550, 300),
    CUSTOM("Custom", listOf(0f, 0f, 0f, 0f, 0f), 0, 0);

    fun toMillibelTargets(minMb: Int, maxMb: Int): List<Int> {
        return normalizedBands.map { ratio ->
            if (ratio >= 0f) {
                (ratio * maxMb).roundToInt()
            } else {
                (-ratio * minMb).roundToInt()
            }.coerceIn(minMb, maxMb)
        }
    }
}

data class AudioFxState(
    val enabled: Boolean = true,
    val preset: EqPreset = EqPreset.FLAT,
    val bandCenterHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val bandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val bassStrength: Int = 0, // 0..1000
    val loudnessGainMb: Int = 0, // 0..1500 (0dB .. +15dB)
    val hardwareAttached: Boolean = false
)

class AudioFxController {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var attachedSessionId: Int = -1

    private val _state = MutableStateFlow(AudioFxState())
    val state: StateFlow<AudioFxState> = _state.asStateFlow()

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == attachedSessionId) return
        releaseEffectsOnly()
        attachedSessionId = audioSessionId

        var hardwareOk = false
        var minMb = -1500
        var maxMb = 1500
        val centerHz = mutableListOf<Int>()

        runCatching {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            hardwareOk = true
            val range = eq.bandLevelRange
            if (range != null && range.size == 2) {
                minMb = range[0].toInt()
                maxMb = range[1].toInt()
            }
            val bands = eq.numberOfBands.toInt().coerceIn(1, 10)
            for (b in 0 until bands) {
                centerHz.add((eq.getCenterFreq(b.toShort()) / 1000).coerceAtLeast(20))
            }
            eq.enabled = _state.value.enabled
        }

        runCatching {
            val bb = BassBoost(0, audioSessionId)
            bassBoost = bb
            bb.enabled = _state.value.enabled && _state.value.bassStrength > 0
        }

        runCatching {
            val le = LoudnessEnhancer(audioSessionId)
            loudnessEnhancer = le
            le.enabled = _state.value.enabled && _state.value.loudnessGainMb > 0
        }

        _state.update { prev ->
            val count = if (centerHz.isNotEmpty()) centerHz.size else prev.bandCenterHz.size
            val adjustedLevels = if (prev.bandLevelsMb.size == count) {
                prev.bandLevelsMb.map { it.coerceIn(minMb, maxMb) }
            } else {
                List(count) { 0 }
            }
            prev.copy(
                bandCenterHz = if (centerHz.isNotEmpty()) centerHz else prev.bandCenterHz,
                bandLevelsMb = adjustedLevels,
                minLevelMb = minMb,
                maxLevelMb = maxMb,
                hardwareAttached = hardwareOk
            )
        }
        syncAllToHardware()
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        syncAllToHardware()
    }

    fun applyPreset(preset: EqPreset) {
        val current = _state.value
        val targets = preset.toMillibelTargets(current.minLevelMb, current.maxLevelMb)
        val sizedTargets = List(current.bandCenterHz.size) { index ->
            targets.getOrElse(index) { 0 }
        }
        _state.update {
            it.copy(
                enabled = true,
                preset = preset,
                bandLevelsMb = sizedTargets,
                bassStrength = preset.defaultBassStrength,
                loudnessGainMb = preset.defaultLoudnessMb
            )
        }
        syncAllToHardware()
    }

    fun setBandLevel(bandIndex: Int, levelMb: Int) {
        _state.update { cur ->
            if (bandIndex !in cur.bandLevelsMb.indices) return@update cur
            val clamped = levelMb.coerceIn(cur.minLevelMb, cur.maxLevelMb)
            val updated = cur.bandLevelsMb.toMutableList().apply { this[bandIndex] = clamped }
            cur.copy(
                enabled = true,
                preset = EqPreset.CUSTOM,
                bandLevelsMb = updated
            )
        }
        syncAllToHardware()
    }

    fun setBassStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _state.update { it.copy(enabled = true, bassStrength = clamped) }
        syncAllToHardware()
    }

    fun setLoudnessGain(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 1500)
        _state.update { it.copy(enabled = true, loudnessGainMb = clamped) }
        syncAllToHardware()
    }

    private fun syncAllToHardware() {
        val st = _state.value
        runCatching {
            equalizer?.let { eq ->
                eq.enabled = st.enabled
                val bands = eq.numberOfBands.toInt()
                for (b in 0 until bands) {
                    val lvl = st.bandLevelsMb.getOrElse(b) { 0 }
                        .coerceIn(st.minLevelMb, st.maxLevelMb)
                        .toShort()
                    eq.setBandLevel(b.toShort(), lvl)
                }
            }
        }
        runCatching {
            bassBoost?.let { bb ->
                bb.enabled = st.enabled && st.bassStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(st.bassStrength.toShort())
                }
            }
        }
        runCatching {
            loudnessEnhancer?.let { le ->
                le.enabled = st.enabled && st.loudnessGainMb > 0
                le.setTargetGain(st.loudnessGainMb)
            }
        }
    }

    private fun releaseEffectsOnly() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
    }

    fun release() {
        releaseEffectsOnly()
        attachedSessionId = -1
    }
}
