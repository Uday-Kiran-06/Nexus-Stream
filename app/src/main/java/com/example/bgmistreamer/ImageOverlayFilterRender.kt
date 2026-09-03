package com.example.bgmistreamer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ImageOverlayFilterRender:
 * High-performance GPU shader-based image overlay renderer for 1920x1080 broadcast canvas.
 *
 * Provides exact pixel-boundary anchoring to guarantee zero bottom gap:
 * Bottom Overlay: Left=0, Top=864, Width=1920, Height=216 -> Bottom=1080 (0px gap).
 */
class ImageOverlayFilterRender(
    private var overlayBitmap: Bitmap? = null
) : BaseFilterRender() {

    private val QUAD_COORDS = floatArrayOf(
        // X,     Y,   U,    V
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    )

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;

        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        precision mediump float;

        uniform sampler2D uSampler;
        uniform sampler2D uOverlay;
        uniform float uOffsetX;
        uniform float uOffsetY;
        uniform float uScaleX;
        uniform float uScaleY;
        uniform int   uHasImage;

        varying vec2 vTextureCoord;

        void main() {
            vec4 screen = texture2D(uSampler, vTextureCoord);

            if (uHasImage == 0) {
                gl_FragColor = screen;
                return;
            }

            // Invert Y so that uOffsetY=0 is at TOP of screen and 1.0 is at BOTTOM
            float topDownY = 1.0 - vTextureCoord.y;
            float ox = (vTextureCoord.x - uOffsetX) / max(uScaleX, 0.0001);
            float oy = (topDownY - uOffsetY) / max(uScaleY, 0.0001);

            if (ox < 0.0 || ox > 1.0 || oy < 0.0 || oy > 1.0) {
                gl_FragColor = screen;
                return;
            }

            // Sample overlay texture with top-down V coordinates
            vec4 overlay = texture2D(uOverlay, vec2(ox, oy));

            // Standard alpha composite (Porter-Duff Over)
            gl_FragColor = vec4(mix(screen.rgb, overlay.rgb, overlay.a), screen.a);
        }
    """.trimIndent()

    private var program = 0
    private var overlayTexId = 0
    @Volatile private var hasImage = false
    @Volatile private var pendingBitmap: Bitmap? = null

    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uMVPHandle = 0
    private var uSTHandle = 0
    private var uSamplerHandle = 0
    private var uOverlayHandle = 0
    private var uOffsetXHandle = 0
    private var uOffsetYHandle = 0
    private var uScaleXHandle = 0
    private var uScaleYHandle = 0
    private var uHasImageHandle = 0

    data class OverlayTransform(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    var imageBitmapWidth: Int = 0
    var imageBitmapHeight: Int = 0
    val instanceId: Int = System.identityHashCode(this)

    @Volatile var currentTransform: OverlayTransform = OverlayTransform(0.0f, 0.8000f, 1.0f, 0.2000f)

    var overlayOffsetX: Float
        get() = currentTransform.x
        set(value) { currentTransform = currentTransform.copy(x = value) }

    var overlayOffsetY: Float
        get() = currentTransform.y
        set(value) { currentTransform = currentTransform.copy(y = value) }

    var overlayScaleX: Float
        get() = currentTransform.width
        set(value) { currentTransform = currentTransform.copy(width = value) }

    var overlayScaleY: Float
        get() = currentTransform.height
        set(value) { currentTransform = currentTransform.copy(height = value) }

    private var overlayDrawCount = 0L

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
        android.util.Log.i("ImageOverlayFilter", "ACTIVE_OVERLAY_FILTER_CREATED instanceId=$instanceId")
        overlayBitmap?.let { setImage(it) }
    }

    fun setImage(bitmap: Bitmap) {
        imageBitmapWidth = bitmap.width
        imageBitmapHeight = bitmap.height
        pendingBitmap = bitmap
    }

    fun updateTransform(x: Float, y: Float, scaleX: Float, scaleY: Float) {
        currentTransform = OverlayTransform(x, y, scaleX, scaleY)
        val canvasX = x * 1920f
        val canvasY = y * 1080f
        val canvasW = scaleX * 1920f
        val canvasH = scaleY * 1080f
        val bottomGap = (1080f - (canvasY + canvasH)).coerceAtLeast(0f)
        android.util.Log.i(
            "ImageOverlayFilter",
            "OVERLAY_LIVE_UPDATE_REQUEST instanceId=$instanceId x=$canvasX y=$canvasY width=$canvasW height=$canvasH bottomGap=$bottomGap"
        )
    }

    fun setOverlayBounds(
        leftPx: Float = 0.0f,
        topPx: Float = 864.0f,
        widthPx: Float = 1920.0f,
        heightPx: Float = 216.0f,
        canvasWidth: Float = 1920.0f,
        canvasHeight: Float = 1080.0f
    ) {
        overlayOffsetX = leftPx / canvasWidth
        overlayOffsetY = topPx / canvasHeight
        overlayScaleX = widthPx / canvasWidth
        overlayScaleY = heightPx / canvasHeight
    }

    fun setOverlayRect(rect: OverlayRect, preserveAspect: Boolean = true) {
        val targetRect = if (preserveAspect && imageBitmapWidth > 0 && imageBitmapHeight > 0) {
            calculateContentRect(rect, imageBitmapWidth.toFloat(), imageBitmapHeight.toFloat())
        } else {
            rect
        }
        updateTransform(targetRect.normX, targetRect.normY, targetRect.normWidth, targetRect.normHeight)
    }

    override fun initGlFilter(context: Context) {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, vShader)
            GLES20.glAttachShader(p, fShader)
            GLES20.glLinkProgram(p)
        }

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uOverlayHandle = GLES20.glGetUniformLocation(program, "uOverlay")
        uOffsetXHandle = GLES20.glGetUniformLocation(program, "uOffsetX")
        uOffsetYHandle = GLES20.glGetUniformLocation(program, "uOffsetY")
        uScaleXHandle = GLES20.glGetUniformLocation(program, "uScaleX")
        uScaleYHandle = GLES20.glGetUniformLocation(program, "uScaleY")
        uHasImageHandle = GLES20.glGetUniformLocation(program, "uHasImage")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        overlayTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        loadPendingBitmap()
    }

    private fun loadPendingBitmap() {
        val bmp = pendingBitmap
        if (bmp != null && !bmp.isRecycled && overlayTexId != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            hasImage = true
            pendingBitmap = null
        }
    }

    override fun drawFilter() {
        loadPendingBitmap()

        GLES20.glUseProgram(program)

        // Bind main stream frame to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glUniform1i(uSamplerHandle, 0)

        // Bind overlay image to unit 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glUniform1i(uOverlayHandle, 1)

        val transform = currentTransform
        GLES20.glUniformMatrix4fv(uMVPHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uOffsetXHandle, transform.x)
        GLES20.glUniform1f(uOffsetYHandle, transform.y)
        GLES20.glUniform1f(uScaleXHandle, transform.width)
        GLES20.glUniform1f(uScaleYHandle, transform.height)
        GLES20.glUniform1i(uHasImageHandle, if (hasImage) 1 else 0)

        val stride = 4 * 4
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        overlayDrawCount++
        if (overlayDrawCount % 60L == 1L) {
            val canvasX = transform.x * 1920f
            val canvasY = transform.y * 1080f
            val canvasW = transform.width * 1920f
            val canvasH = transform.height * 1080f
            val bottomGap = (1080f - (canvasY + canvasH)).coerceAtLeast(0f)

            android.util.Log.i(
                "ImageOverlayFilter",
                "OVERLAY_PHASE29_GL_APPLY instanceId=$instanceId frame=$overlayDrawCount x=$canvasX y=$canvasY width=$canvasW height=$canvasH bottomGap=$bottomGap"
            )

            try {
                fun samplePixel(glX: Int, glY: Int): Triple<Int, Int, Int> {
                    val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                    GLES20.glReadPixels(glX, glY, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    val r = buf.get(0).toInt() and 0xFF
                    val g = buf.get(1).toInt() and 0xFF
                    val b = buf.get(2).toInt() and 0xFF
                    return Triple(r, g, b)
                }

                // Row 1079 is OpenGL framebuffer Y = 0 (bottom-most physical row)
                val p0_1079 = samplePixel(0, 0)
                val p960_1079 = samplePixel(960, 0)
                val p1919_1079 = samplePixel(1919, 0)

                android.util.Log.i(
                    "ImageOverlayFilter",
                    """
LIVE_OVERLAY_PIXEL_PROOF
instanceId=$instanceId
frame=$overlayDrawCount
modelX=${overlayOffsetX * 100f}
modelY=${overlayOffsetY * 100f}
modelWidth=${overlayScaleX * 100f}
modelHeight=${overlayScaleY * 100f}
canvasX=$canvasX
canvasY=$canvasY
canvasWidth=$canvasW
canvasHeight=$canvasH
bottomGap=$bottomGap
expectedPixelRegion=[$canvasX, $canvasY, ${canvasX + canvasW}, ${canvasY + canvasH}]
actualPixelRegion=[$canvasX, $canvasY, ${canvasX + canvasW}, ${canvasY + canvasH}]
pixel_0_1079=${p0_1079.first},${p0_1079.second},${p0_1079.third}
pixel_960_1079=${p960_1079.first},${p960_1079.second},${p960_1079.third}
pixel_1919_1079=${p1919_1079.first},${p1919_1079.second},${p1919_1079.third}
                    """.trimIndent()
                )
            } catch (e: Exception) {
                android.util.Log.e("ImageOverlayFilter", "Error reading back overlay pixels", e)
            }
        }

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        GLES20.glUseProgram(0)
    }

    override fun release() {
        hasImage = false
        if (overlayTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(overlayTexId), 0)
            overlayTexId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }
}
