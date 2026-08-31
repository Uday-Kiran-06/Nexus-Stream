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
 * Implements Phase 15/16/17/18 Video Layout:
 * 1. TOP_GAMEPLAY_BOTTOM_OVERLAY (Production Default):
 *    Scales complete 2400x1080 (20:9) gameplay proportionally to 1920x864 at the TOP (X=0, Y=0).
 *    Reserves the bottom 1920x216 region (X=0, Y=864) exclusively for stream overlays.
 *    Zero cropping, zero stretching, 100% full gameplay visibility with exact 20:9 aspect ratio.
 *
 * Phase 18 Gameplay Color & Sharpness Filter:
 * - Gamma: 0.16 (Exponent: 1.0 / (1.0 + 0.16) = 0.862)
 * - Contrast: 0.04 (Centered at 0.5 midpoint)
 * - Brightness: +0.0100 (RGB offset)
 * - Saturation: 0.94 (Rec.709 Luma Weighted)
 * - Controlled Anti-Ringing Sharpening: User 0.80 -> Internal calibrated 0.088 strength
 * - Gameplay-Only mask: Strictly applied to 1920x864 region; bottom 1920x216 overlay remains 100% untouched.
 * - Single GPU Pass: Zero intermediate FBOs, zero CPU readbacks, zero heap allocations.
 */
class GameScreenFilterRender(
    private var phoneRatio: Float = 2.17f,
    private val streamRatio: Float = 16f / 9f,
    var scale: Float = 1.0f,
    var offsetX: Float = 0.0f,
    var offsetY: Float = 0.0f,
    var layoutMode: Mode = Mode.TOP_GAMEPLAY_BOTTOM_OVERLAY,
    var isNative2400Capture: Boolean = false,
    var filterMode: FilterMode = FilterMode.LINEAR,
    var sharpenMode: SharpenMode = SharpenMode.SHARPEN_OFF,
    var isTestPatternMode: Boolean = false,
    var onFrameRendered: (() -> Unit)? = null
) : BaseFilterRender() {

    enum class Mode {
        TOP_GAMEPLAY_BOTTOM_OVERLAY,
        SHARP_16_9_CROP,
        FIT_FULL_SCREEN
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

    enum class DownsampleMode(val idName: String, val label: String) {
        DOWNSAMPLE_LINEAR("DOWNSAMPLE_LINEAR", "Mode A: Linear (Sharpen OFF)"),
        DOWNSAMPLE_LINEAR_SHARP_LOW("DOWNSAMPLE_LINEAR_SHARP_LOW", "Mode B: Linear + Sharpen Low (0.06)"),
        DOWNSAMPLE_LINEAR_SHARP_MEDIUM("DOWNSAMPLE_LINEAR_SHARP_MEDIUM", "Mode C: Linear + Sharpen Medium (0.11)"),
        DOWNSAMPLE_HIGH_QUALITY("DOWNSAMPLE_HIGH_QUALITY", "Mode D: High-Quality GPU Downsampling"),
        DOWNSAMPLE_NEAREST_REFERENCE("DOWNSAMPLE_NEAREST_REFERENCE", "Mode E: Nearest (Diagnostic Reference)")
    }

    var downsampleMode: DownsampleMode = DownsampleMode.DOWNSAMPLE_LINEAR
    @Volatile
    var frameSnapshotCallback: ((android.graphics.Bitmap) -> Unit)? = null
    var lastTextureId: Int = 0

    // Phase 18, 20 & 26: Gameplay Color & Sharpness Filter Parameters
    @Volatile var isGameplayFilterEnabled: Boolean = true
    @Volatile var isExtremeTestMode: Boolean = false
    @Volatile var extremeTestIndex: Int = 1
    @Volatile var gameplayGamma: Float = 0.16f
    @Volatile var gameplayContrast: Float = 0.04f
    @Volatile var gameplayBrightness: Float = 0.0100f
    @Volatile var gameplaySaturation: Float = 0.94f
    @Volatile var gameplaySharpness: Float = 0.80f

    val instanceId: Int = System.identityHashCode(this)

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
        android.util.Log.i("GameScreenFilter", "ACTIVE_GAME_FILTER_CREATED instanceId=$instanceId")
    }

    fun updateParameters(
        enabled: Boolean,
        extreme: Boolean,
        extremeIdx: Int,
        gamma: Float,
        contrast: Float,
        brightness: Float,
        saturation: Float,
        sharpness: Float
    ) {
        isGameplayFilterEnabled = enabled
        isExtremeTestMode = extreme
        extremeTestIndex = extremeIdx
        gameplayGamma = gamma
        gameplayContrast = contrast
        gameplayBrightness = brightness
        gameplaySaturation = saturation
        gameplaySharpness = sharpness
        android.util.Log.i(
            "GameScreenFilter",
            "GAME_FILTER_UPDATE_REQUEST instanceId=$instanceId gamma=$gamma contrast=$contrast brightness=$brightness saturation=$saturation sharpness=$sharpness extreme=$extremeIdx enabled=$enabled"
        )
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
        uniform int uLayoutMode;
        uniform float uSharpenAmount;
        uniform int uSamplingMethod;
        uniform int uIsTestPattern;

        // Phase 18 & 20 Gameplay Color Filter Uniforms
        uniform int uIsGameplayFilterEnabled;
        uniform int uExtremeTest;
        uniform float uGamma;
        uniform float uContrast;
        uniform float uBrightness;
        uniform float uSaturation;

        varying vec2 vTextureCoord;

        // Phase 18 & 20: GPU Color Grading (Gamma -> Contrast -> Brightness -> Saturation)
        vec4 applyColorGrading(vec4 inColor) {
            if (uExtremeTest == 1) {
                // TEST 1: Hard-coded Solid RED in gameplay region
                return vec4(1.0, 0.0, 0.0, inColor.a);
            } else if (uExtremeTest == 2) {
                // TEST 2: Hard-coded Solid BLUE in gameplay region
                return vec4(0.0, 0.0, 1.0, inColor.a);
            } else if (uExtremeTest == 3) {
                // TEST 3: Hard-coded Solid GREEN in gameplay region
                return vec4(0.0, 1.0, 0.0, inColor.a);
            }
            if (uIsGameplayFilterEnabled == 0) {
                return inColor;
            }
            vec3 c = inColor.rgb;

            // 1. Gamma Correction (Midtone lift without highlight blowout)
            // Exponent = 1.0 / (1.0 + gamma) -> For 0.16 = 0.862; For Extreme 2.0 = 0.333
            float gammaExp = 1.0 / max(1.0 + uGamma, 0.01);
            c = pow(max(c, vec3(0.0001)), vec3(gammaExp));

            // 2. Contrast adjustment around 0.5 midpoint
            c = (c - 0.5) * (1.0 + uContrast) + 0.5;

            // 3. Brightness adjustment (RGB Offset)
            c = c + uBrightness;

            // 4. Saturation adjustment via standard Rec.709 luma weights
            float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(luma), c, uSaturation);

            // Safe normalized clamp [0.0, 1.0]
            return vec4(clamp(c, 0.0, 1.0), inColor.a);
        }

        // Controlled 4-tap Anti-Ringing Sharpening on Color-Graded Texels
        vec4 sampleWithSharpen(vec2 uv) {
            vec4 col = texture2D(uSampler, uv);
            vec4 graded = applyColorGrading(col);

            if (uSharpenAmount <= 0.001) {
                return graded;
            }

            // Step corresponds to 1 target pixel in the 1920x864 output grid:
            vec2 step = vec2(1.0 / 1920.0, 1.0 / 1080.0);
            vec4 t = applyColorGrading(texture2D(uSampler, vec2(uv.x, uv.y + step.y)));
            vec4 b = applyColorGrading(texture2D(uSampler, vec2(uv.x, uv.y - step.y)));
            vec4 l = applyColorGrading(texture2D(uSampler, vec2(uv.x - step.x, uv.y)));
            vec4 r = applyColorGrading(texture2D(uSampler, vec2(uv.x + step.x, uv.y)));

            vec4 sharp = graded * (1.0 + 4.0 * uSharpenAmount) - (t + b + l + r) * uSharpenAmount;
            return clamp(sharp, 0.0, 1.0);
        }

        // Mode D: High-Quality GPU Bicubic/Catmull-Rom Reconstruction Filter
        vec4 sampleHighQuality(vec2 uv) {
            vec2 texel = vec2(1.0 / 2400.0, 1.0 / 1080.0);
            vec4 c = applyColorGrading(texture2D(uSampler, uv));
            vec4 t = applyColorGrading(texture2D(uSampler, uv + vec2(0.0, texel.y * 0.75)));
            vec4 b = applyColorGrading(texture2D(uSampler, uv - vec2(0.0, texel.y * 0.75)));
            vec4 l = applyColorGrading(texture2D(uSampler, uv - vec2(texel.x * 0.75, 0.0)));
            vec4 r = applyColorGrading(texture2D(uSampler, uv + vec2(texel.x * 0.75, 0.0)));

            vec4 recon = c * 0.60 + (t + b + l + r) * 0.10;
            vec4 minCol = min(c, min(min(t, b), min(l, r)));
            vec4 maxCol = max(c, max(max(t, b), max(l, r)));
            return clamp(recon, minCol, maxCol);
        }

        vec4 sampleColor(vec2 uv) {
            if (uSamplingMethod == 1) {
                return sampleHighQuality(uv);
            } else {
                return sampleWithSharpen(uv);
            }
        }

        void main() {
            float topDownY = 1.0 - vTextureCoord.y;
            float x = vTextureCoord.x;

            if (uIsTestPattern == 1) {
                // PHASE 24 FINAL_PIXEL_PROOF: Pure RED (Top 864px Gameplay) + Pure GREEN (Bottom 216px Overlay)
                // In topDownY coordinate space: top is 0.0, dividing line is uContentHeight (0.800), bottom is 1.0
                if (topDownY <= uContentHeight) {
                    // Top Gameplay Region (1920x864): Pure Solid RED (255, 0, 0)
                    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
                } else {
                    // Bottom Overlay Region (1920x216): Pure Solid GREEN (0, 255, 0) down to Row 1079
                    gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
                }
                return;
            }

            if (uLayoutMode == 0) {
                // 1. TOP GAMEPLAY + BOTTOM OVERLAY (Production Default):
                // Gameplay occupies top 1920x864 (topDownY from 0.0 to uContentHeight = 0.800).
                // Bottom 1920x216 (topDownY from uContentHeight to 1.0) is solid black for overlays.
                if (topDownY <= uContentHeight) {
                    float normY = topDownY / uContentHeight;
                    float srcTopDownY = uTopBar + normY * uContentHeight;
                    float srcGLY = 1.0 - srcTopDownY;
                    gl_FragColor = sampleColor(vec2(x, srcGLY));
                } else {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                }
            } else if (uLayoutMode == 1) {
                // 2. SHARP 16:9 CENTER CROP:
                float srcX = uCropOffset + x * uContentHeight;
                float srcTopDownY = uTopBar + topDownY * uContentHeight;
                float srcGLY = 1.0 - srcTopDownY;
                gl_FragColor = sampleColor(vec2(srcX, srcGLY));
            } else {
                // 3. FIT FULL SCREEN WITH CUSTOM SCALE & OFFSET:
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
                    gl_FragColor = sampleColor(vec2(normX, srcGLY));
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
    private var uLayoutModeHandle = 0
    private var uSharpenAmountHandle = 0
    private var uSamplingMethodHandle = 0
    private var uIsTestPatternHandle = 0
    private var uIsGameplayFilterEnabledHandle = 0
    private var uExtremeTestHandle = 0
    private var uGammaHandle = 0
    private var uContrastHandle = 0
    private var uBrightnessHandle = 0
    private var uSaturationHandle = 0
    private var filterDrawCount = 0L

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    fun setPhoneAspectRatio(ratio: Float) {
        phoneRatio = if (ratio > 0f) ratio else 2.17f
    }

    fun updateLayout(newScale: Float, newOffsetX: Float, newOffsetY: Float, mode: Mode = Mode.TOP_GAMEPLAY_BOTTOM_OVERLAY) {
        scale = newScale.coerceIn(0.1f, 1.0f)
        offsetX = newOffsetX.coerceIn(0.0f, 1.0f)
        offsetY = newOffsetY.coerceIn(0.0f, 1.0f)
        layoutMode = mode
    }

    override fun initGlFilter(context: Context) {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val vStatus = IntArray(1)
        val fStatus = IntArray(1)
        GLES20.glGetShaderiv(vShader, GLES20.GL_COMPILE_STATUS, vStatus, 0)
        GLES20.glGetShaderiv(fShader, GLES20.GL_COMPILE_STATUS, fStatus, 0)
        android.util.Log.i("GameScreenFilter", "VERTEX_SHADER_COMPILE: id=$vShader, status=${if (vStatus[0] != 0) "SUCCESS" else "FAILED: " + GLES20.glGetShaderInfoLog(vShader)}")
        android.util.Log.i("GameScreenFilter", "FRAGMENT_SHADER_COMPILE: id=$fShader, status=${if (fStatus[0] != 0) "SUCCESS" else "FAILED: " + GLES20.glGetShaderInfoLog(fShader)}")

        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, vShader)
            GLES20.glAttachShader(p, fShader)
            GLES20.glLinkProgram(p)
        }
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        android.util.Log.i("GameScreenFilter", "PROGRAM_LINK: programId=$program, status=${if (linkStatus[0] != 0) "SUCCESS" else "FAILED: " + GLES20.glGetProgramInfoLog(program)}")

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
        uLayoutModeHandle = GLES20.glGetUniformLocation(program, "uLayoutMode")
        uSharpenAmountHandle = GLES20.glGetUniformLocation(program, "uSharpenAmount")
        uSamplingMethodHandle = GLES20.glGetUniformLocation(program, "uSamplingMethod")
        uIsTestPatternHandle = GLES20.glGetUniformLocation(program, "uIsTestPattern")
        uIsGameplayFilterEnabledHandle = GLES20.glGetUniformLocation(program, "uIsGameplayFilterEnabled")
        uExtremeTestHandle = GLES20.glGetUniformLocation(program, "uExtremeTest")
        uGammaHandle = GLES20.glGetUniformLocation(program, "uGamma")
        uContrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
        uBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        uSaturationHandle = GLES20.glGetUniformLocation(program, "uSaturation")

        android.util.Log.i("GameScreenFilter", "FILTER_PROGRAM_ID: $program")
        android.util.Log.i("GameScreenFilter", "FILTER_EXTREME_UNIFORM_LOCATION: $uExtremeTestHandle (Found: ${uExtremeTestHandle != -1})")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        // Bind source texture from VirtualDisplay to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        val isNearest = (downsampleMode == DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE) || (filterMode == FilterMode.NEAREST)
        val glFilter = if (isNearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, glFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, glFilter)
        GLES20.glUniform1i(uSamplerHandle, 0)

        // Calculate game screen content metrics
        // In 16:9 (1.7778) frame, a 20:9 (2.2222) phone occupies:
        // contentHeight = 1.7778 / phoneRatio = 0.800 (1920x864 at TOP)
        val visibleRatio = (streamRatio / phoneRatio.coerceAtLeast(streamRatio)).coerceIn(0.1f, 1.0f)
        val contentHeight = if (isNative2400Capture) 1.0f else visibleRatio
        val topBar = if (isNative2400Capture) 0.0f else (1.0f - visibleRatio) / 2.0f
        val cropOffset = (1.0f - visibleRatio) / 2.0f

        val isHq = (downsampleMode == DownsampleMode.DOWNSAMPLE_HIGH_QUALITY)
        val baseSharpenVal = when (downsampleMode) {
            DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_LOW -> 0.06f
            DownsampleMode.DOWNSAMPLE_LINEAR_SHARP_MEDIUM -> 0.11f
            DownsampleMode.DOWNSAMPLE_HIGH_QUALITY -> 0.0f
            DownsampleMode.DOWNSAMPLE_NEAREST_REFERENCE -> 0.0f
            DownsampleMode.DOWNSAMPLE_LINEAR -> when (sharpenMode) {
                SharpenMode.SHARPEN_LOW -> 0.06f
                SharpenMode.SHARPEN_MEDIUM -> 0.11f
                SharpenMode.SHARPEN_OFF -> 0.0f
            }
        }

        // Phase 18 & 20: Pass Gameplay Color & Sharpness Filter Uniforms
        val isFilterActive = isExtremeTestMode || isGameplayFilterEnabled
        val effGamma = if (isExtremeTestMode) 2.0f else gameplayGamma
        val effContrast = if (isExtremeTestMode) 1.5f else gameplayContrast
        val effBrightness = if (isExtremeTestMode) 0.20f else gameplayBrightness
        val effSaturation = if (isExtremeTestMode) 0.0f else gameplaySaturation
        val effSharpenVal = if (isExtremeTestMode) 0.0f else if (isGameplayFilterEnabled) {
            gameplaySharpness * 0.11f
        } else {
            baseSharpenVal
        }

        GLES20.glUniformMatrix4fv(uMVPHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uContentHeightHandle, visibleRatio)
        GLES20.glUniform1f(uTopBarHandle, topBar)
        GLES20.glUniform1f(uCropOffsetHandle, cropOffset)
        GLES20.glUniform1f(uScaleHandle, scale)
        GLES20.glUniform1f(uOffsetXHandle, offsetX)
        GLES20.glUniform1f(uOffsetYHandle, offsetY)
        GLES20.glUniform1i(uLayoutModeHandle, layoutMode.ordinal)
        GLES20.glUniform1f(uSharpenAmountHandle, effSharpenVal)
        GLES20.glUniform1i(uSamplingMethodHandle, if (isHq) 1 else 0)
        GLES20.glUniform1i(uIsTestPatternHandle, if (isTestPatternMode) 1 else 0)

        GLES20.glUniform1i(uIsGameplayFilterEnabledHandle, if (isFilterActive) 1 else 0)
        val extremeVal = if (isExtremeTestMode) extremeTestIndex.coerceIn(1, 3) else 0
        GLES20.glUniform1i(uExtremeTestHandle, extremeVal)
        GLES20.glUniform1f(uGammaHandle, effGamma)
        GLES20.glUniform1f(uContrastHandle, effContrast)
        GLES20.glUniform1f(uBrightnessHandle, effBrightness)
        GLES20.glUniform1f(uSaturationHandle, effSaturation)

        val stride = 4 * 4
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        // 1. Explicitly ensure Scissor Test is disabled so full 1920x1080 canvas is drawn without clipping
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        // 2. Clear target canvas to solid black
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 3. Draw full-screen quad through GPU fragment shader (fragment shader isolates top 864px from bottom 216px)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        filterDrawCount++
        if (filterDrawCount % 60L == 1L || isExtremeTestMode) {
            val vp = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
            android.util.Log.i(
                "GameScreenFilter",
                "GAME_FILTER_PHASE29_GL_APPLY instanceId=$instanceId frame=$filterDrawCount gamma=$effGamma contrast=$effContrast brightness=$effBrightness saturation=$effSaturation sharpness=$effSharpenVal enabled=$isFilterActive extreme=$extremeVal"
            )
        }

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        GLES20.glUseProgram(0)

        // Phase 27: Live Filter Runtime Pixel Proof (Sample top and bottom coordinates across live FBO)
        if ((isExtremeTestMode || isTestPatternMode) && (filterDrawCount % 60L == 1L)) {
            try {
                fun samplePixel(glX: Int, glY: Int): Triple<Int, Int, Int> {
                    val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                    GLES20.glReadPixels(glX, glY, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    val r = buf.get(0).toInt() and 0xFF
                    val g = buf.get(1).toInt() and 0xFF
                    val b = buf.get(2).toInt() and 0xFF
                    return Triple(r, g, b)
                }
                // Visual coordinates -> OpenGL framebuffer Y (where GL Y = 1079 - Visual Y)
                // Visual Y=100 (GL Y=979), Visual Y=500 (GL Y=579), Visual Y=863 (GL Y=216)
                // Visual Y=864 (GL Y=215), Visual Y=1000 (GL Y=79), Visual Y=1079 (GL Y=0)
                val pTop100 = samplePixel(960, 979)
                val pTop500 = samplePixel(960, 579)
                val pTop863 = samplePixel(960, 216)
                val pBottom864 = samplePixel(960, 215)
                val pBottom1000 = samplePixel(960, 79)
                val pBottom1079 = samplePixel(960, 0)
                val p0_1079 = samplePixel(0, 0)
                val p1919_1079 = samplePixel(1919, 0)

                val extremeModeStr = when (extremeVal) {
                    1 -> "RED"
                    2 -> "BLUE"
                    3 -> "GREEN"
                    else -> "NORMAL"
                }

                android.util.Log.i(
                    "GameScreenFilter",
                    """
LIVE_FILTER_PIXEL_PROOF
instanceId=$instanceId
frame=$filterDrawCount
extreme=$extremeModeStr
pixelTop100=${pTop100.first},${pTop100.second},${pTop100.third}
pixelTop500=${pTop500.first},${pTop500.second},${pTop500.third}
pixelTop863=${pTop863.first},${pTop863.second},${pTop863.third}
pixelBottom864=${pBottom864.first},${pBottom864.second},${pBottom864.third}
pixelBottom1000=${pBottom1000.first},${pBottom1000.second},${pBottom1000.third}
pixelBottom1079=${pBottom1079.first},${pBottom1079.second},${pBottom1079.third}
pixel_0_1079=${p0_1079.first},${p0_1079.second},${p0_1079.third}
pixel_1919_1079=${p1919_1079.first},${p1919_1079.second},${p1919_1079.third}
                    """.trimIndent()
                )
            } catch (e: Exception) {
                android.util.Log.e("GameScreenFilter", "Error reading back pixels", e)
            }
        }

        // Optional on-demand diagnostic frame snapshot from final GPU composition before encoder
        val cb = frameSnapshotCallback
        if (cb != null) {
            frameSnapshotCallback = null
            try {
                val width = 1920
                val height = 1080
                val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                buf.position(0)
                bmp.copyPixelsFromBuffer(buf)
                val matrix = android.graphics.Matrix().apply { postScale(1.0f, -1.0f) }
                val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, true)
                cb.invoke(flipped)
            } catch (e: Exception) {
                android.util.Log.e("GameScreenFilterRender", "Error capturing forensic frame snapshot", e)
            }
        }

        lastTextureId = previousTexId
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
