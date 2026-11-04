package com.yegorkapomidorka.headswap.aa

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.yegorkapomidorka.headswap.AnnexB
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AA H.264 -> MediaCodec without Surface -> YUV in memory. frame go to decoder in its original form (before editing frame_num).
 * The lost frame (the queue is full, the codec error) breaks the reference picture, so after losing to the next IDR from AA
 * nothing is served or given. The picture needs coded size: lines for crop SPS are also reference, and decoder
 * (Qualcomm, AA 1920x1080 = 1088 lines with crop 8) gives only visible. Therefore, decoder gets SPS without crop, and in
 * CODEC_CONFIG, and inside IDR from AA. It did not work to copy frame whole - insert is impossible: bridge once
 * [RefPictureSink.onRefUnavailable], decoder stops.
 */
class RefDecoder(private val sink: RefPictureSink, private val log: (String) -> Unit) : RefPictureDecoder {
    private sealed class Item {
        class Config(val data: ByteArray) : Item()
        class Frame(val data: ByteArray, val seq: Long) : Item()
        object Stop : Item()
    }

    private val queue = LinkedBlockingQueue<Item>(QUEUE)
    @Volatile private var running = true

    // only thread decoder
    private var codec: MediaCodec? = null
    private var codecCfg: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var needIdr = true
    private var pic: IdrSplice.Picture? = null
    private var codedW = 0
    private var codedH = 0
    private var failed = false
    private var formatLogged = false

    init {
        Thread({ loop() }, "aa-ref-dec").start()      // after fields: thread reads them right away
    }

    override fun feedConfig(annexB: ByteArray) {
        if (!queue.offer(Item.Config(annexB))) lose("очередь полна на CODEC_CONFIG")
    }

    override fun feed(annexB: ByteArray, seq: Long) {
        if (!running) return
        if (!queue.offer(Item.Frame(annexB, seq))) lose("очередь полна (декодер не успевает)")
    }

    override fun stop() {
        running = false
        queue.clear()
        queue.offer(Item.Stop)
    }

    /** With the alien thread: queue cleaned, thread decoder will see Lost and will wait for IDR. */
    private fun lose(why: String) {
        queue.clear()
        queue.offer(Item.Frame(LOST, -1))
        log("свой IDR: декодер опорных картинок потерял кадр ($why), жду IDR от AA")
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                when (val item = queue.poll(5, TimeUnit.MILLISECONDS)) {
                    is Item.Config -> onConfig(item.data)
                    is Item.Frame -> onFrame(item, info)
                    Item.Stop -> break
                    null -> {}
                }
                drain(info)
            }
        } catch (e: Exception) {
            log("свой IDR: !!!!!! нить декодера: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            release()
        }
    }

    private fun onConfig(data: ByteArray) {
        val (s, p) = H264Stream.spsPps(uncropped(data))
        if (s != null) sps = s
        if (p != null) pps = p
    }

    private fun onFrame(f: Item.Frame, info: MediaCodec.BufferInfo) {
        if (f.data === LOST) {
            needIdr = true
            return
        }
        val idr = 5 in AnnexB.nalTypes(f.data)
        if (needIdr && !idr) return
        val data = if (idr) uncropped(f.data) else f.data
        if (idr) {
            val (s, p) = H264Stream.spsPps(data)
            if (s != null) sps = s
            if (p != null) pps = p
            val cfg = (sps ?: return) + (pps ?: return)
            if (codec == null || !cfg.contentEquals(codecCfg)) {
                if (!create(sps!!, pps!!)) return
                codecCfg = cfg
            }
            needIdr = false
        }
        val c = codec ?: return
        // decoder sometimes freezes at tens to hundreds of ms (realme h588). reset to IDR from AA is up to a minute without its IDR,
        // so wait for the buffer, unloading the output; frame AA meanwhile accumulate in line
        val t0 = System.nanoTime()
        var idx = c.dequeueInputBuffer(INPUT_WAIT_US)
        while (idx < 0 && running && System.nanoTime() - t0 < INPUT_GIVEUP_NS) {
            drain(info)
            idx = c.dequeueInputBuffer(INPUT_WAIT_US)
        }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        if (idx < 0) {
            needIdr = true
            log("свой IDR: нет входного буфера декодера $waitedMs мс, жду IDR от AA")
            return
        }
        if (waitedMs >= INPUT_SLOW_LOG_MS) log("свой IDR: входной буфер декодера ждали $waitedMs мс")
        val buf = c.getInputBuffer(idx)!!
        if (data.size > buf.capacity()) {
            c.queueInputBuffer(idx, 0, 0, 0, 0)
            needIdr = true
            log("свой IDR: кадр ${data.size} Б больше входного буфера ${buf.capacity()} Б, жду IDR от AA")
            return
        }
        buf.clear()
        buf.put(data)
        c.queueInputBuffer(idx, 0, data.size, f.seq, 0)
    }

    /** SPS in frame or config - without crop (P-frame AA SPS do not carry). Did not understand - as is: a copy of frame will not work then. */
    private fun uncropped(au: ByteArray): ByteArray = SpsCrop.mapSps(AnnexB.normalize(au)) { sps ->
        try {
            SpsCrop.uncrop(sps)
        } catch (e: SpsCrop.SpsException) {
            log("свой IDR: кроп из SPS не снят (${e.message})")
            sps
        }
    }

    private fun create(s: ByteArray, p: ByteArray): Boolean {
        release()
        val nal = IdrSplicer.nals(s).firstOrNull() ?: return false
        val info = try {
            IdrSplice.parseSps(nal)
        } catch (e: RuntimeException) {
            log("свой IDR: SPS не разобран (${e.message})")
            return false
        }
        codedW = info.codedW
        codedH = info.codedH
        return try {
            val f = MediaFormat.createVideoFormat(MIME, codedW, codedH)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(s))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(p))
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Qualcomm without them keeps the last frame until the next entry (realme h588): on the static screen AA is silent, there are no pictures.
            // Alien decoder Unknown Keys Skips
            for (k in QTI_LOW_LATENCY) f.setInteger(k, 1)
            val c = MediaCodec.createDecoderByType(MIME)
            try {
                c.configure(f, null, null, 0)
                c.start()
            } catch (e: Exception) {
                c.release()
                throw e
            }
            codec = c
            formatLogged = false
            val vendor = if (Build.VERSION.SDK_INT >= 31) {
                try {
                    c.supportedVendorParameters.filter { it in QTI_LOW_LATENCY }.ifEmpty { listOf("нет") }.joinToString()
                } catch (_: Exception) {
                    "?"
                }
            } else "?"
            log("свой IDR: декодер опорных картинок ${c.name} ${codedW}x$codedH, низкая задержка Qualcomm: $vendor")
            true
        } catch (e: Exception) {
            log("свой IDR: !!!!!! декодер не создан: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        val c = codec ?: return
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!formatLogged) log("свой IDR: формат вывода декодера ${c.outputFormat}")
                formatLogged = true
                continue
            }
            if (idx < 0) return
            val seq = info.presentationTimeUs
            if (info.size > 0 && !needIdr && !failed && sink.wantsRefPicture(seq)) {
                val img = c.getOutputImage(idx)
                val p = if (img != null) copy(img) else null
                img?.close()
                c.releaseOutputBuffer(idx, false)
                if (p != null) sink.onRefPicture(seq, p)
            } else {
                c.releaseOutputBuffer(idx, false)
            }
        }
    }

    /** Image YUV_420_888 -> I420 encoded size; null - less lines in buffer than in coded frame. */
    private fun copy(img: Image): IdrSplice.Picture? {
        val p = pic?.takeIf { it.width == codedW && it.height == codedH }
            ?: IdrSplice.Picture(codedW, codedH, ByteArray(codedW * codedH), ByteArray(codedW * codedH / 4), ByteArray(codedW * codedH / 4)).also { pic = it }
        val pl = img.planes
        val ok = pl.size == 3 &&
            copyPlane(pl[0], codedW, codedH, p.y) &&
            copyPlane(pl[1], codedW / 2, codedH / 2, p.u) &&
            copyPlane(pl[2], codedW / 2, codedH / 2, p.v)
        if (!ok) {
            // inserting until the end of the AA-session is impossible: the bridge ceases to wait for pictures, decoder is no longer needed
            failed = true
            running = false
            sink.onRefUnavailable("вывод декодера ${img.width}x${img.height} (format ${img.format}) не покрывает кодированный кадр ${codedW}x$codedH")
            return null
        }
        return p
    }

    private fun copyPlane(plane: Image.Plane, w: Int, h: Int, dst: ByteArray): Boolean {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val base = buf.position()
        if (buf.limit() - base < (h - 1) * rs + (w - 1) * ps + 1) return false
        if (ps == 1) {
            for (y in 0 until h) {
                buf.position(base + y * rs)
                buf.get(dst, y * w, w)
            }
        } else {
            for (y in 0 until h) {
                val row = base + y * rs
                for (x in 0 until w) dst[y * w + x] = buf.get(row + x * ps)
            }
        }
        buf.position(base)
        return true
    }

    private fun release() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (_: Exception) {
        }
        try {
            c.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MIME = "video/avc"
        private val QTI_LOW_LATENCY = listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
        private const val QUEUE = 120
        private const val INPUT_WAIT_US = 50_000L
        private const val INPUT_GIVEUP_NS = 2_000_000_000L   // The Longer Line ( )120 frame, ~4 (c) Will still be full
        private const val INPUT_SLOW_LOG_MS = 200L
        private val LOST = ByteArray(0)
    }
}
