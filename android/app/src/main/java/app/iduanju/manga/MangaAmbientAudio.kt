package app.iduanju.manga

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import app.iduanju.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class MangaAtmosphere(val label: String) {
    OFF("关闭"),
    RAIN("雨天窗景"),
    TRAIN("列车车窗"),
    FIREWORK("新年烟火"),
    SNOW("雪夜"),
}

/**
 * 沉浸氛围 环境音效 + 翻页音效. 雨天 / 列车 / 烟火 loop bundled ambient recordings (res/raw/ambient_*.mp3);
 * 雪夜 has no recording and is synthesised as soft filtered wind. The page flip uses res/raw/page_flip.wav via SoundPool.
 * Nothing plays until [attachContext]; [release] ends the playback loop for good.
 */
class MangaAmbientAudioPlayer {
    private data class Request(
        val scene: MangaAtmosphere = MangaAtmosphere.OFF, val volume: Float = 0f, val context: Context? = null,
        val generation: Long = 0, val released: Boolean = false,
    ) {
        /** [other] is still this playback session (only its volume may differ). */
        fun sameSession(other: Request) = other.volume > 0f && other.generation == generation && other.context === context
    }

    private val state = MutableStateFlow(Request())
    private var soundPool: SoundPool? = null
    private var pageFlipId = 0

    init { CoroutineScope(Dispatchers.Main).launch { playbackLoop() } }

    private suspend fun playbackLoop() {
        while (true) {
            val request = state.first { it.released || (it.volume > 0f && it.context != null) }
            if (request.released) return
            val ok = runCatching {
                if (request.scene == MangaAtmosphere.SNOW) withContext(Dispatchers.IO) { playSynth(request) } else playRecording(request)
            }.getOrDefault(false)
            // A broken decoder / audio device must not spin: wait for the next settings change.
            if (!ok) state.first { it != request }
        }
    }

    @Synchronized fun attachContext(context: Context) {
        if (state.value.released) return
        val app = context.applicationContext
        state.update { it.copy(context = app) }
        if (soundPool != null) return
        soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(audioAttributes()).build().also { pool ->
            pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) pageFlipId = sampleId }
            pool.load(app, R.raw.page_flip, 1)
        }
    }

    @Synchronized fun playPageFlip(volume: Float = 0.72f) {
        val gain = volume.coerceIn(0f, 1f)
        if (pageFlipId != 0 && gain > 0f) soundPool?.play(pageFlipId, gain, gain, 1, 0, 1f)
    }

    fun setState(atmosphere: MangaAtmosphere, soundEnabled: Boolean, volume: Float) {
        val gain = if (soundEnabled && atmosphere != MangaAtmosphere.OFF) volume.coerceIn(0f, 1f) else 0f
        state.update {
            if (it.released) it
            else it.copy(scene = atmosphere, volume = gain, generation = it.generation + if (it.scene != atmosphere || gain == 0f) 1 else 0)
        }
    }

    fun pause() = setState(MangaAtmosphere.OFF, false, 0f)

    @Synchronized fun release() {
        state.value = Request(released = true)
        soundPool?.release()
        soundPool = null
        pageFlipId = 0
    }

    /** Loops the scene's recording, following volume changes, until the session ends. False if it cannot play. */
    private suspend fun playRecording(request: Request): Boolean {
        val resource = when (request.scene) {
            MangaAtmosphere.RAIN -> R.raw.ambient_rain
            MangaAtmosphere.TRAIN -> R.raw.ambient_train
            MangaAtmosphere.FIREWORK -> R.raw.ambient_firework
            else -> return false
        }
        val player = MediaPlayer.create(request.context, resource) ?: return false
        try {
            val failed = MutableStateFlow(false)
            player.setOnErrorListener { _, _, _ -> failed.value = true; true }
            player.isLooping = true
            player.setVolume(request.volume, request.volume)
            player.start()
            state.combine(failed, ::Pair).first { (next, error) ->
                val stop = error || !request.sameSession(next)
                if (!stop) player.setVolume(next.volume, next.volume)
                stop
            }
            return !failed.value
        } finally {
            player.release()
        }
    }

    /** 雪夜: streams synthesised wind on the calling (IO) thread with a click-free gain ramp. */
    private fun playSynth(request: Request): Boolean {
        val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, format.channelMask, format.encoding)
        if (minimum <= 0) return false
        val pcm = ShortArray(SAMPLE_RATE / 50)
        val track = AudioTrack.Builder().setAudioAttributes(audioAttributes()).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, pcm.size * 4)).build()
        try {
            val wind = SnowWind()
            var gain = 0f
            track.play()
            while (true) {
                val current = state.value
                if (!request.sameSession(current)) return true
                for (index in pcm.indices) {
                    gain += (current.volume - gain).coerceIn(-FADE_STEP, FADE_STEP)
                    pcm[index] = ((wind.next() * gain).coerceIn(-1f, 1f) * 32767).toInt().toShort()
                }
                if (track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) <= 0) return false
            }
        } finally {
            track.release()
        }
    }
}

private fun audioAttributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
private const val SAMPLE_RATE = 22050
private const val FADE_STEP = 25f / SAMPLE_RATE

/** Band-passed noise (fast minus slow low-pass) with a slow 0.15 Hz swell. */
private class SnowWind {
    private var rumble = 0f
    private var air = 0f
    private var frame = 0L
    fun next(): Float {
        val white = Random.nextFloat() * 2f - 1f
        rumble += 0.0253f * (white - rumble)
        air += 0.32f * (white - air)
        val seconds = (frame++ % (20 * SAMPLE_RATE)).toDouble() / SAMPLE_RATE
        return (air - rumble) * (0.105f + 0.035f * sin(2 * PI * 0.15 * seconds).toFloat())
    }
}
