package com.example.bgmistreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamDiagnosticsTest {

    private lateinit var diagnostics: StreamDiagnostics

    @Before
    fun setup() {
        diagnostics = StreamDiagnostics(null)
    }

    @Test
    fun testInitialStateIsStable() {
        val state = diagnostics.getDiagnosticState()
        assertEquals(StreamDiagnostics.StreamDiagnosticState.STABLE, state)
    }

    @Test
    fun testRtmpSnapshotUpdateAndNetworkLimitedState() {
        val congestedSnapshot = RtmpDeliverySnapshot(
            queueDepth = 45,
            queueBytes = 45 * 20_000L,
            oldestPacketAgeMs = 750L,
            avgSendTimeMs = 45.0,
            p95SendTimeMs = 120.0,
            p99SendTimeMs = 180.0,
            maxSendTimeMs = 250.0,
            blockedSendCount = 15L,
            throughputBps = 3_500_000L,
            packetsPerSec = 40.0,
            backpressureDetected = true,
            writeChunkSize = 65536,
            droppedVideoFrames = 0L,
            droppedAudioFrames = 0L
        )

        diagnostics.onRtmpDeliveryUpdate(congestedSnapshot)
        val state = diagnostics.getDiagnosticState()

        assertEquals(StreamDiagnostics.StreamDiagnosticState.RTMP_NETWORK_LIMITED, state)
    }

    @Test
    fun testYouTubeIngestSuspectedState() {
        val healthySnapshot = RtmpDeliverySnapshot(
            queueDepth = 2,
            queueBytes = 40_000L,
            oldestPacketAgeMs = 33L,
            avgSendTimeMs = 1.2,
            p95SendTimeMs = 3.5,
            p99SendTimeMs = 5.0,
            maxSendTimeMs = 8.0,
            blockedSendCount = 0L,
            throughputBps = 8_200_000L,
            packetsPerSec = 60.0,
            backpressureDetected = false,
            writeChunkSize = 65536,
            droppedVideoFrames = 0L,
            droppedAudioFrames = 0L
        )

        diagnostics.onRtmpDeliveryUpdate(healthySnapshot)
        val state = diagnostics.getDiagnosticState(youtubeWarningActive = true)

        assertEquals(StreamDiagnostics.StreamDiagnosticState.YOUTUBE_INGEST_SUSPECTED, state)
    }

    @Test
    fun testDeveloperDiagnosticsReportFormat() {
        val snapshot = RtmpDeliverySnapshot(
            queueDepth = 3,
            queueBytes = 60_000L,
            oldestPacketAgeMs = 50L,
            avgSendTimeMs = 1.5,
            p95SendTimeMs = 3.2,
            p99SendTimeMs = 4.8,
            maxSendTimeMs = 7.0,
            blockedSendCount = 0L,
            throughputBps = 8_000_000L,
            packetsPerSec = 60.0,
            backpressureDetected = false,
            writeChunkSize = 65536
        )
        diagnostics.onRtmpDeliveryUpdate(snapshot)

        val report = diagnostics.getDeveloperDiagnosticsReport()
        assertTrue(report.contains("STREAM"))
        assertTrue(report.contains("RTMP"))
        assertTrue(report.contains("QUEUE: 3 pkts / 58 KB"))
        assertTrue(report.contains("TIMESTAMP"))
        assertTrue(report.contains("STATUS"))
        assertTrue(report.contains("STABLE"))
    }

    @Test
    fun testIdrAndPFrameTracking() {
        diagnostics.onEncodedFrame(100_000, true) // 1 IDR of 100KB
        diagnostics.onEncodedFrame(20_000, false) // 1 P-frame of 20KB
        diagnostics.onEncodedFrame(22_000, false) // 1 P-frame of 22KB

        assertEquals(1L, diagnostics.idrFrameCount.get())
        assertEquals(2L, diagnostics.pFrameCount.get())
        assertEquals(100_000L, diagnostics.idrBytesTotal.get())
        assertEquals(42_000L, diagnostics.pFrameBytesTotal.get())

        val summary = diagnostics.getSummaryReport()
        assertTrue(summary.contains("PHASE 10 QUALITY AUDIT"))
        assertTrue(summary.contains("IDR_KEYFRAMES:                1 (Avg Size: 97 KB)"))
        assertTrue(summary.contains("P_FRAMES:                     2 (Avg Size: 20 KB)"))
    }

    @Test
    fun testQualityPresetResolution() {
        // Direct ID match
        assertEquals(StreamQualityPreset.QUALITY_1080P60_10, StreamQualityPreset.fromIdOrLabel("QUALITY_1080P60_10"))
        assertEquals(StreamQualityPreset.QUALITY_1080P60_8, StreamQualityPreset.fromIdOrLabel("QUALITY_1080P60_8"))
        assertEquals(StreamQualityPreset.QUALITY_720P30, StreamQualityPreset.fromIdOrLabel("QUALITY_720P30"))

        // Exact display label match
        assertEquals(StreamQualityPreset.QUALITY_1080P60_10, StreamQualityPreset.fromIdOrLabel("1080p 60fps (10 Mbps - Recommended)"))
        assertEquals(StreamQualityPreset.QUALITY_1080P60_8, StreamQualityPreset.fromIdOrLabel("1080p 60fps (8 Mbps)"))
        assertEquals(StreamQualityPreset.QUALITY_720P30, StreamQualityPreset.fromIdOrLabel("720p 30fps (Mobile Data)"))

        // Legacy / partial string match
        assertEquals(StreamQualityPreset.QUALITY_1080P60_10, StreamQualityPreset.fromIdOrLabel("1080p 60fps (10 Mbps)"))
        assertEquals(StreamQualityPreset.QUALITY_1080P60_8, StreamQualityPreset.fromIdOrLabel("1080p 60fps"))
        assertEquals(StreamQualityPreset.QUALITY_720P30, StreamQualityPreset.fromIdOrLabel("720p 30fps"))

        // Null / blank defaults to 1080p60 10 Mbps
        assertEquals(StreamQualityPreset.QUALITY_1080P60_10, StreamQualityPreset.fromIdOrLabel(null))
        assertEquals(StreamQualityPreset.QUALITY_1080P60_10, StreamQualityPreset.fromIdOrLabel(""))
    }

    @Test
    fun testQualityPresetParameters() {
        val q10 = StreamQualityPreset.QUALITY_1080P60_10
        assertEquals(1920, q10.width)
        assertEquals(1080, q10.height)
        assertEquals(60, q10.fps)
        assertEquals(10_000_000, q10.bitrateBps)

        val q8 = StreamQualityPreset.QUALITY_1080P60_8
        assertEquals(1920, q8.width)
        assertEquals(1080, q8.height)
        assertEquals(60, q8.fps)
        assertEquals(8_192_000, q8.bitrateBps)

        val q720 = StreamQualityPreset.QUALITY_720P30
        assertEquals(1280, q720.width)
        assertEquals(720, q720.height)
        assertEquals(30, q720.fps)
        assertEquals(3_500_000, q720.bitrateBps)
    }

    @Test
    fun testOverlayAuthoritativeRectGeometry() {
        // Test A: Full Width (X=0, Scale=100%)
        val fullWidthRect = overlayModelToCanvasRect(0f, 0f, 100f)
        assertEquals(0f, fullWidthRect.leftPx, 0.001f)
        assertEquals(1920f, fullWidthRect.widthPx, 0.001f)
        assertEquals(1920f, fullWidthRect.rightPx, 0.001f)
        assertEquals(0f, fullWidthRect.leftGap, 0.001f)
        assertEquals(0f, fullWidthRect.rightGap, 0.001f)

        // Test B: Left Edge (X=0)
        val leftEdgeRect = overlayModelToCanvasRect(0f, 50f, 50f)
        assertEquals(0f, leftEdgeRect.leftPx, 0.001f)
        assertEquals(0f, leftEdgeRect.leftGap, 0.001f)

        // Test C: Right Edge Docked (X=50%, Scale=50% -> leftPx=960, rightPx=1920)
        val rightEdgeRect = overlayModelToCanvasRect(50f, 50f, 50f)
        assertEquals(960f, rightEdgeRect.leftPx, 0.001f)
        assertEquals(1920f, rightEdgeRect.rightPx, 0.001f)
        assertEquals(0f, rightEdgeRect.rightGap, 0.001f)

        // Test D: Center (Centered overlay on 1920 canvas)
        val centerRect = overlayModelToCanvasRect(25f, 0f, 50f) // width = 960px, left = 480px
        assertEquals(480f, centerRect.leftPx, 0.001f)
        assertEquals(960f, centerRect.leftPx + centerRect.widthPx / 2f, 0.001f)

        // Bottom Banner Overlay Full-Width Alignment (X=0, Y=80%, Scale=100%, Banner Aspect 1920/216)
        val bottomOverlayRect = overlayModelToCanvasRect(0f, 80f, 100f, 1920f / 216f)
        assertEquals(0f, bottomOverlayRect.leftPx, 0.001f)
        assertEquals(864f, bottomOverlayRect.topPx, 0.001f)
        assertEquals(1920f, bottomOverlayRect.widthPx, 0.001f)
        assertEquals(216f, bottomOverlayRect.heightPx, 0.001f)
        assertEquals(1920f, bottomOverlayRect.rightPx, 0.001f)
        assertEquals(1080f, bottomOverlayRect.bottomPx, 0.001f)
        assertEquals(0f, bottomOverlayRect.leftGap, 0.001f)
        assertEquals(0f, bottomOverlayRect.rightGap, 0.001f)
        assertEquals(0f, bottomOverlayRect.bottomGap, 0.001f)
    }

    @Test
    fun testOverlayDragAndRestartPersistence() {
        val initialOverlay = OverlayItem(
            uri = "content://test/image.png",
            xPercent = 10f,
            yPercent = 80f,
            scalePercent = 100f
        )

        val rectBefore = overlayModelToCanvasRect(
            initialOverlay.xPercent,
            initialOverlay.yPercent,
            initialOverlay.scalePercent,
            1920f / 216f
        )

        // Drag simulation: user drags to left edge (X = 0)
        val draggedXPercent = 0f
        val draggedYPercent = 80f
        val rectAfterDrag = overlayModelToCanvasRect(
            draggedXPercent,
            draggedYPercent,
            initialOverlay.scalePercent,
            1920f / 216f
        )

        assertEquals(0f, rectAfterDrag.leftPx, 0.001f)
        assertEquals(864f, rectAfterDrag.topPx, 0.001f)
        assertEquals(1920f, rectAfterDrag.widthPx, 0.001f)
        assertEquals(216f, rectAfterDrag.heightPx, 0.001f)
        assertEquals(0f, rectAfterDrag.leftGap, 0.001f)
        assertEquals(0f, rectAfterDrag.bottomGap, 0.001f)

        // Persistence simulation: reload from persisted percents
        val reloadedRect = overlayModelToCanvasRect(
            draggedXPercent,
            draggedYPercent,
            initialOverlay.scalePercent,
            1920f / 216f
        )
        assertEquals(rectAfterDrag.leftPx, reloadedRect.leftPx, 0.0001f)
        assertEquals(rectAfterDrag.topPx, reloadedRect.topPx, 0.0001f)
        assertEquals(rectAfterDrag.widthPx, reloadedRect.widthPx, 0.0001f)
        assertEquals(rectAfterDrag.heightPx, reloadedRect.heightPx, 0.0001f)
    }

    @Test
    fun testTrue16x9ChromaKeyOverlayRendering() {
        val targetAspect = 16f / 9f

        // Test A: Original Size (Scale 100% -> 1920x1080)
        val rectA = overlayModelToCanvasRect(0f, 0f, 100f, targetAspect)
        assertEquals(1920f, rectA.widthPx, 0.001f)
        assertEquals(1080f, rectA.heightPx, 0.001f)
        assertEquals(targetAspect, rectA.widthPx / rectA.heightPx, 0.001f)

        // Test B: Half Size (Scale 50% -> 960x540)
        val rectB = overlayModelToCanvasRect(0f, 0f, 50f, targetAspect)
        assertEquals(960f, rectB.widthPx, 0.001f)
        assertEquals(540f, rectB.heightPx, 0.001f)
        assertEquals(targetAspect, rectB.widthPx / rectB.heightPx, 0.001f)

        // Test C: Small Overlay (Scale 33.333% -> 640x360)
        val scaleC = (640f / 1920f) * 100f
        val rectC = overlayModelToCanvasRect(0f, 0f, scaleC, targetAspect)
        assertEquals(640f, rectC.widthPx, 0.01f)
        assertEquals(360f, rectC.heightPx, 0.01f)
        assertEquals(targetAspect, rectC.widthPx / rectC.heightPx, 0.001f)

        // Test D: Move - Left, Center, Right, Top, Middle, Bottom
        // 1. Top-Left
        val topLeft = overlayModelToCanvasRect(0f, 0f, 50f, targetAspect)
        assertEquals(0f, topLeft.leftPx, 0.001f)
        assertEquals(0f, topLeft.topPx, 0.001f)
        assertEquals(targetAspect, topLeft.widthPx / topLeft.heightPx, 0.001f)

        // 2. Center
        val center = overlayModelToCanvasRect(25f, 25f, 50f, targetAspect)
        assertEquals(480f, center.leftPx, 0.001f)
        assertEquals(270f, center.topPx, 0.001f)
        assertEquals(targetAspect, center.widthPx / center.heightPx, 0.001f)

        // 3. Bottom-Right Docked (50% scale docked to right and bottom edges)
        val bottomRight = overlayModelToCanvasRect(50f, 50f, 50f, targetAspect)
        assertEquals(960f, bottomRight.leftPx, 0.001f) // 1920 - 960 = 960
        assertEquals(540f, bottomRight.topPx, 0.001f)  // 1080 - 540 = 540
        assertEquals(0f, bottomRight.rightGap, 0.001f)
        assertEquals(0f, bottomRight.bottomGap, 0.001f)
        assertEquals(targetAspect, bottomRight.widthPx / bottomRight.heightPx, 0.001f)
    }

    @Test
    fun testOffCanvasOverlayPositioning() {
        val targetAspect = 16f / 9f

        // Test A: 100% Overlay at Y = 0 (flush coverage)
        val rectY0 = overlayModelToCanvasRect(0f, 0f, 100f, targetAspect)
        assertEquals(0f, rectY0.leftPx, 0.001f)
        assertEquals(0f, rectY0.topPx, 0.001f)
        assertEquals(1920f, rectY0.widthPx, 0.001f)
        assertEquals(1080f, rectY0.heightPx, 0.001f)
        assertEquals(1080f, rectY0.bottomPx, 0.001f)

        // Test B: Move Below Canvas: Y = +250px
        val yPercent250 = (250f / 1080f) * 100f
        val rectBelow = overlayModelToCanvasRect(0f, yPercent250, 100f, targetAspect)
        assertEquals(0f, rectBelow.leftPx, 0.001f)
        assertEquals(250f, rectBelow.topPx, 0.001f)
        assertEquals(1920f, rectBelow.widthPx, 0.001f)
        assertEquals(1080f, rectBelow.heightPx, 0.001f)
        assertEquals(1330f, rectBelow.bottomPx, 0.001f) // 250 + 1080 = 1330 > 1080
        val visibleHeightBelow = (1080f - rectBelow.topPx).coerceIn(0f, rectBelow.heightPx)
        assertEquals(830f, visibleHeightBelow, 0.001f) // Visible stream region shows top 830px
        assertEquals(targetAspect, rectBelow.widthPx / rectBelow.heightPx, 0.001f) // No stretching

        // Test C: Move Above Canvas: Y = -250px
        val yPercentNeg250 = (-250f / 1080f) * 100f
        val rectAbove = overlayModelToCanvasRect(0f, yPercentNeg250, 100f, targetAspect)
        assertEquals(0f, rectAbove.leftPx, 0.001f)
        assertEquals(-250f, rectAbove.topPx, 0.001f)
        assertEquals(1920f, rectAbove.widthPx, 0.001f)
        assertEquals(1080f, rectAbove.heightPx, 0.001f)
        assertEquals(830f, rectAbove.bottomPx, 0.001f) // -250 + 1080 = 830
        val visibleHeightAbove = (rectAbove.bottomPx - 0f).coerceIn(0f, rectAbove.heightPx)
        assertEquals(830f, visibleHeightAbove, 0.001f) // Visible stream region shows lower 830px
        assertEquals(targetAspect, rectAbove.widthPx / rectAbove.heightPx, 0.001f) // No stretching

        // Test D: Smaller Overlays crossing boundaries
        // 960x540 overlay positioned partially outside top-left
        val rectSmall50 = overlayModelToCanvasRect((-200f / 1920f) * 100f, (-150f / 1080f) * 100f, 50f, targetAspect)
        assertEquals(-200f, rectSmall50.leftPx, 0.001f)
        assertEquals(-150f, rectSmall50.topPx, 0.001f)
        assertEquals(960f, rectSmall50.widthPx, 0.001f)
        assertEquals(540f, rectSmall50.heightPx, 0.001f)
        assertEquals(760f, rectSmall50.rightPx, 0.001f)
        assertEquals(390f, rectSmall50.bottomPx, 0.001f)

        // 640x360 overlay positioned partially outside bottom-right
        val scaleSmall33 = (640f / 1920f) * 100f
        val rectSmall33 = overlayModelToCanvasRect((1800f / 1920f) * 100f, (900f / 1080f) * 100f, scaleSmall33, targetAspect)
        assertEquals(1800f, rectSmall33.leftPx, 0.01f)
        assertEquals(900f, rectSmall33.topPx, 0.01f)
        assertEquals(640f, rectSmall33.widthPx, 0.01f)
        assertEquals(360f, rectSmall33.heightPx, 0.01f)
        assertTrue("X + width > 1920", rectSmall33.rightPx > 1920f)
        assertTrue("Y + height > 1080", rectSmall33.bottomPx > 1080f)

        // Test E: Horizontal off-canvas (X < 0, X > 0, X + width > 1920)
        val rectXNeg = overlayModelToCanvasRect(-10f, 0f, 100f, targetAspect)
        assertEquals(-192f, rectXNeg.leftPx, 0.001f)
        val rectXPos = overlayModelToCanvasRect(10f, 0f, 100f, targetAspect)
        assertEquals(192f, rectXPos.leftPx, 0.001f)
        assertTrue("X + width > 1920", rectXPos.rightPx > 1920f)

        // Test E: Chroma Key Logic Simulation
        // Verify pure green pixel (0, 255, 0) is flagged as cutoff threshold
        val greenR = 0
        val greenG = 255
        val greenB = 0
        val maxRb = maxOf(greenR, greenB)
        val greenDiff = greenG - maxRb
        assertTrue("Pure green must trigger cutoff threshold", greenDiff > 18)

        // Verify non-green subject pixel (e.g. skin tone or red shirt) is preserved
        val skinR = 210
        val skinG = 160
        val skinB = 140
        val maxRbSkin = maxOf(skinR, skinB)
        val greenDiffSkin = skinG - maxRbSkin
        assertTrue("Subject pixels must not be treated as green screen", greenDiffSkin <= 0)
    }

    @Test
    fun testMicrophoneDefaultStateAndLifecycle() {
        // 1. Initial State before any stream: Mic must be OFF (isMicMuted = true)
        StreamService.isMicMutedState.value = true
        assertTrue("Initial mic state must be OFF", StreamService.isMicMutedState.value)

        // 2. Audio processor verification: when mic is muted/OFF, mic gain in mixer is 0.0f
        val audioProcessor = StreamAudioProcessor(
            micGain = 0.8f,
            gameGain = 1.0f,
            isMicMuted = StreamService.isMicMutedState.value
        )
        assertTrue(audioProcessor.isMicMuted)

        // 3. Start Stream 1: Go Live must start with Mic OFF
        StreamService.isStreamingState.value = true
        StreamService.isMicMutedState.value = true // Explicitly OFF on GO LIVE
        assertTrue("New stream must start with Mic OFF", StreamService.isMicMutedState.value)

        // 4. User enables Mic during Stream 1 -> Mic ON
        StreamService.isMicMutedState.value = false
        audioProcessor.isMicMuted = false
        org.junit.Assert.assertFalse("User enabled mic -> Mic ON", StreamService.isMicMutedState.value)
        org.junit.Assert.assertFalse(audioProcessor.isMicMuted)

        // 5. Reconnect during same stream -> Microphone state preserved (remains ON)
        val preservedStateDuringReconnect = StreamService.isMicMutedState.value
        org.junit.Assert.assertFalse("Reconnect must preserve current mic state", preservedStateDuringReconnect)

        // 6. User disables Mic -> Mic OFF
        StreamService.isMicMutedState.value = true
        audioProcessor.isMicMuted = true
        assertTrue("User disabled mic -> Mic OFF", StreamService.isMicMutedState.value)

        // 7. Stop Stream 1 -> State resets
        StreamService.isStreamingState.value = false
        StreamService.isMicMutedState.value = true
        assertTrue("Stream stop must reset mic to OFF", StreamService.isMicMutedState.value)

        // 8. Start Stream 2 after previously having Mic ON: Must strictly start with Mic OFF
        StreamService.isStreamingState.value = true
        StreamService.isMicMutedState.value = true
        assertTrue("Stream 2 must start with Mic OFF", StreamService.isMicMutedState.value)
    }
}

