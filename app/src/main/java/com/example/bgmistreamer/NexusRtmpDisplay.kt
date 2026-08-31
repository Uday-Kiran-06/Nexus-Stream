package com.example.bgmistreamer

import android.content.Context
import android.media.MediaCodec
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpDisplay
import java.nio.ByteBuffer

/**
 * NexusRtmpDisplay:
 * Lightweight instrumentation subclass of RtmpDisplay.
 *
 * Intercepts exact MediaCodec output BufferInfo (presentationTimeUs, size, flags)
 * and SPS/PPS sequence headers to feed real-time PTS & FPS diagnostics
 * with zero heap allocations in the critical path and zero streaming latency.
 */
class NexusRtmpDisplay(
    context: Context,
    useOpenGl: Boolean,
    connectChecker: ConnectChecker
) : RtmpDisplay(context, useOpenGl, connectChecker) {

    var onVideoBufferInfo: ((MediaCodec.BufferInfo, Int) -> Unit)? = null
    var onAudioBufferInfo: ((MediaCodec.BufferInfo, Int) -> Unit)? = null
    var onSpsPpsVpsInfo: ((ByteBuffer, ByteBuffer, ByteBuffer?) -> Unit)? = null

    override fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        onVideoBufferInfo?.invoke(info, info.size)
        super.getH264DataRtp(h264Buffer, info)
    }

    override fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        onAudioBufferInfo?.invoke(info, info.size)
        super.getAacDataRtp(aacBuffer, info)
    }

    override fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
        onSpsPpsVpsInfo?.invoke(sps, pps, vps)
        super.onSpsPpsVpsRtp(sps, pps, vps)
    }
}
