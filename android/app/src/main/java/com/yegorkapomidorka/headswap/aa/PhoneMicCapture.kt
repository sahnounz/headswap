package com.yegorkapomidorka.headswap.aa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.yegorkapomidorka.headswap.AppLog

/**
 * Phone microphone (spare source for AA): AudioRecord VOICE_RECOGNITION, 16 kHz mono PCM16.
 * Blocks by 2048 samples (4096 B). You need a resolution of RECORD_AUDIO; in the background of Android 11+ silently gives silence,
 * Therefore, the log is the peak signal of the first blocks: zero peak = the system jams the microphone.
 */
class PhoneMicCapture(private val ctx: Context) : PhoneMic {
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var running = false

    @Synchronized
    override fun start(sink: (ByteArray) -> Unit): Boolean {
        if (running) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i("MIC phone: нет разрешения RECORD_AUDIO (дай в UI)", true)
            return false
        }
        val min = AudioRecord.getMinBufferSize(AaMicFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            AppLog.i("MIC phone: getMinBufferSize=$min, 16 кГц моно не поддерживается", true)
            return false
        }
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, AaMicFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, AaMicFormat.CHUNK_BYTES * 2)
            )
        } catch (e: Exception) {
            AppLog.e("MIC phone: AudioRecord не создан", e)
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.i("MIC phone: AudioRecord не инициализирован (state=${r.state})", true)
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            AppLog.e("MIC phone: startRecording", e)
            r.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            AppLog.i("MIC phone: запись не началась (recordingState=${r.recordingState}): микрофон занят?", true)
            r.release()
            return false
        }
        rec = r
        running = true
        val t = Thread({ loop(r, sink) }, "phone-mic")
        t.isDaemon = true
        t.start()
        AppLog.i("MIC phone: AudioRecord VOICE_RECOGNITION ${AaMicFormat.SAMPLE_RATE} Гц моно запущен", true)
        return true
    }

    private fun loop(r: AudioRecord, sink: (ByteArray) -> Unit) {
        val buf = ByteArray(AaMicFormat.CHUNK_BYTES)
        var blocks = 0
        var total = 0L
        var peakAll = 0
        // thread lives while rec is her record: after stop() and immediately start() (repeated open) running again true,
        // and by one flag the old thread would spin the stopped AudioRecord without freeing it
        while (running && rec === r) {
            val n = r.read(buf, 0, buf.size)
            if (rec !== r) break               // stop() during read: tail stopped recording do not give
            if (n <= 0) {
                if (n < 0 && running) AppLog.i("MIC phone: read вернул $n, останавливаюсь", true)
                if (n < 0) break
                continue
            }
            blocks++
            total += n
            var peak = 0
            var i = 0
            while (i + 1 < n) {
                val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                i += 2
            }
            if (peak > peakAll) peakAll = peak
            if (blocks == 1 || blocks == 10) AppLog.i("MIC phone: блок #$blocks $n Б, пик=$peak (0 = тишина/микрофон заглушен системой)", false)
            sink(buf.copyOf(n))
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        AppLog.i("MIC phone: поток записи закончен: блоков $blocks, $total Б, максимальный пик $peakAll", true)
    }

    /** Not waiting for thread recording (its sink can stand on the router lock): thread itself will release AudioRecord. */
    @Synchronized
    override fun stop() {
        running = false
        val r = rec
        rec = null
        try {
            r?.stop()
        } catch (_: Exception) {
        }
    }
}
