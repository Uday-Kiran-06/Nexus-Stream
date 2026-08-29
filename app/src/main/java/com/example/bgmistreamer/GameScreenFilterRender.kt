package com.example.bgmistreamer

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GameScreenFilterRender:
 * High-Fidelity GPU Video Transformer for mobile game streaming.
 *
 * Implements two framing modes:
 * 1. SHARP_16_9_CROP (Default):
 *    Preserves 100% full vertical 1080p resolution and maps pixels 1:1 to the 16:9 canvas
 *    by center-cropping the left/right excess margins of ultra-wide mobile displays.
 *    Provides maximum HUD/text sharpness, crystal-clear foliage, and zero aspect ratio distortion.
 *
 * 2. FIT_FULL_SCREEN:
 *    Scales the entire ultra-wide screen into the 16:9 frame (with top/bottom space for overlays).
 */
class GameScreenFilterRender(
    private var phoneRatio: Float = 2.17f,
    private val streamRatio: Float = 16f / 9f,
    var scale: Float = 1.0f,
    var offsetX: Float = 0.0f,
    var offsetY: Float = 0.0f,
    var isSharpCropMode: Boolean = true,
    var isNative2400Capture: Boolean = false,
    var filterMode: FilterMode = FilterMode.LINEAR,
    var sharpenMode: SharpenMode = SharpenMode.SHARPEN_OFF,
    var isTestPatternMode: Boolean = false,
    var onFrameRendered: (() -> Unit)? = null
) : BaseFilterRender() {

    enum class Mode {
        SHARP_16_9_CROP,
        FIT_FULL_SCREEN
    }

    enum class CaptureMode {
        CURRENT_PRODUCTION,
        NATIVE_2400_EXPERIMENT
    }

    enum class FilterMode {
        LINEAR,
        NEAREST
    }

    enum class SharpenMode {
        SHARPEN_OFF,
        SHARPEN_LOW,
        SHARPEN_MEDIUM
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
        uniform float uContentHeight;
        uniform float uTopBar;
        uniform float uCropOffset;
        uniform float uScale;
        uniform float uOffsetX;
        uniform float uOffsetY;
        uniform int uIsSharpCrop;
        uniform float uSharpenAmount;
        uniform int uIsTestPattern;

        varying vec2 vTextureCoord;

        vec4 sampleWithSharpen(vec2 uv) {
            vec4 col = texture2D(uSampler, uv);
            if (uSharpenAmount <= 0.001) {
                return col;
            }
            vec2 step = vec2(1.0 / 1920.0, 1.0 / 1080.0);
            vec4 t = texture2D(uSampler, vec2(uv.x, uv.y + step.y));
            vec4 b = texture2D(uSampler, vec2(uv.x, uv.y - step.y));
            vec4 l = texture2D(uSampler, vec2(uv.x - step.x, uv.y));
            vec4 r = texture2D(uSampler, vec2(uv.x + step.x, uv.y));
            vec4 sharp = col * (1.0 + 4.0 * uSharpenAmount) - (t + b + l + r) * uSharpenAmount;
            return clamp(sharp, 0.0, 1.0);
        }

        void main() {
            float topDownY = 1.0 - vTextureCoord.y;
            float x = vTextureCoord.x;

            if (uIsTestPattern == 1) {
                // Procedural spatial resolution test pattern (1px grid, 2px bars, checkerboard, diagonals)
                vec2 pixelCoord = vec2(x * 1920.0, (1.0 - topDownY) * 1080.0);
                float line1pxH = mod(floor(pixelCoord.y), 2.0);
                float line1pxV = mod(floor(pixelCoord.x), 2.0);
                float checker8px = mod(floor(pixelCoord.x / 8.0) + floor(pixelCoord.y / 8.0), 2.0);
                float diag45 = mod(floor(pixelCoord.x + pixelCoord.y), 4.0);

                if (pixelCoord.y < 270.0) {
                    // 1-pixel alternating lines
                    float val = (pixelCoord.x < 960.0) ? line1pxH : line1pxV;
                    gl_FragColor = vec4(vec3(val), 1.0);
                } else if (pixelCoord.y < 540.0) {
                    // 8-pixel checkerboard
                    gl_FragColor = vec4(vec3(checker8px), 1.0);
                } else if (pixelCoord.y < 810.0) {
                    // 45-degree fine diagonal lines
                    float val = (diag45 < 2.0) ? 1.0 : 0.0;
                    gl_FragColor = vec4(vec3(val), 1.0);
                } else {
                    // 2-pixel and 4-pixel stepped bars
                    float bar4px = mod(floor(pixelCoord.x / 4.0), 2.0);
                    gl_FragColor = vec4(bar4px, bar4px, 1.0 - bar4px, 1.0);
                }
                return;
            }

            if (uIsSharpCrop == 1) {
                // SHARP 16:9 CENTER CROP: 1:1 pixel mapping without vertical shrinking
                float srcX = uCropOffset + x * uContentHeight;
                float srcTopDownY = uTopBar + topDownY * uContentHeight;
                float srcGLY = 1.0 - srcTopDownY;

                gl_FragColor = sampleWithSharpen(vec2(srcX, srcGLY));
            } else {
                // FIT FULL SCREEN MODE: scaled with user offset and overlay space
                float outW = uScale;
                float outH = uScale * uContentHeight;
                float minX = uOffsetX;
                float maxX = uOffsetX + outW;
                float minY = uOffsetY;
                float maxY = uOffsetY + outH;

                if (x >= minX && x <= maxX && topDownY >= minY && topDownY <= maxY) {
                    float normX = (x - minX) / outW;
                    float normY = (topDownY - minY) / outH;
                    float srcTopDownY = uTopBar + normY * uContentHeight;
                    float srcGLY = 1.0 - srcTopDownY;

                    gl_FragColor = sampleWithSharpen(vec2(normX, srcGLY));
                } else {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                }
            }
        }
    """.trimIndent()

    private val QUAD_COORDS = floatArrayOf(
        // X,     Y,   U,    V
        -1.0f, -1.0f, 0.0f, 0.0f, // Bottom-left
         1.0f, -1.0f, 1.0f, 0.0f, // Bottom-right
        -1.0f,  1.0f, 0.0f, 1.0f, // Top-left
         1.0f,  1.0f, 1.0f, 1.0f  // Top-right
    )

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    private var program = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uMVPHandle = 0
    private var uSTHandle = 0
    private var uSamplerHandle = 0
    private var uContentHeightHandle = 0
    private var uTopBarHandle = 0
    private var uCropOffsetHandle = 0
    private var uScaleHandle = 0
    private var uOffsetXHandle = 0
    private var uOffsetYHandle = 0
    private var uIsSharpCropHandle = 0
    private var uSharpenAmountHandle = 0
    private var uIsTestPatternHandle = 0

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    fun setPhoneAspectRatio(ratio: Float) {
        phoneRatio = if (ratio > 0f) ratio else 2.17f
    }

    fun updateLayout(newScale: Float, newOffsetX: Float, newOffsetY: Float, sharpCrop: Boolean = true) {
        scale = newScale.coerceIn(0.1f, 1.0f)
        offsetX = newOffsetX.coerceIn(0.0f, 1.0f)
        offsetY = newOffsetY.coerceIn(0.0f, 1.0f)
        isSharpCropMode = sharpCrop
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
        uContentHeightHandle = GLES20.glGetUniformLocation(program, "uContentHeight")
        uTopBarHandle = GLES20.glGetUniformLocation(program, "uTopBar")
        uCropOffsetHandle = GLES20.glGetUniformLocation(program, "uCropOffset")
        uScaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        uOffsetXHandle = GLES20.glGetUniformLocation(program, "uOffsetX")
        uOffsetYHandle = GLES20.glGetUniformLocation(program, "uOffsetY")
        uIsSharpCropHandle = GLES20.glGetUniformLocation(program, "uIsSharpCrop")
        uSharpenAmountHandle = GLES20.glGetUniformLocation(program, "uSharpenAmount")
        uIsTestPatternHandle = GLES20.glGetUniformLocation(program, "uIsTestPattern")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        // Bind source texture from VirtualDisplay to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        val glFilter = if (filterMode == FilterMode.NEAREST) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, glFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, glFilter)
        GLES20.glUniform1i(uSamplerHandle, 0)

        // Calculate game screen content metrics
        // In 16:9 (1.7778) frame, a wide phone (e.g. 20:9 / 2.17:1) occupies:
        // contentHeight = 1.7778 / phoneRatio
        val visibleRatio = (streamRatio / phoneRatio.coerceAtLeast(streamRatio)).coerceIn(0.1f, 1.0f)
        val contentHeight = if (isNative2400Capture) 1.0f else visibleRatio
        val topBar = if (isNative2400Capture) 0.0f else (1.0f - visibleRatio) / 2.0f
        val cropOffset = (1.0f - visibleRatio) / 2.0f

        val sharpenVal = when (sharpenMode) {
            SharpenMode.SHARPEN_LOW -> 0.15f
            SharpenMode.SHARPEN_MEDIUM -> 0.30f
            SharpenMode.SHARPEN_OFF -> 0.0f
        }

        GLES20.glUniformMatrix4fv(uMVPHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uContentHeightHandle, contentHeight)
        GLES20.glUniform1f(uTopBarHandle, topBar)
        GLES20.glUniform1f(uCropOffsetHandle, cropOffset)
        GLES20.glUniform1f(uScaleHandle, scale)
        GLES20.glUniform1f(uOffsetXHandle, offsetX)
        GLES20.glUniform1f(uOffsetYHandle, offsetY)
        GLES20.glUniform1i(uIsSharpCropHandle, if (isSharpCropMode) 1 else 0)
        GLES20.glUniform1f(uSharpenAmountHandle, sharpenVal)
        GLES20.glUniform1i(uIsTestPatternHandle, if (isTestPatternMode) 1 else 0)

        val stride = 4 * 4
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        onFrameRendered?.invoke()
    }

    override fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("GameScreenFilter Shader Error: $log")
        }
        return shader
    }
}
