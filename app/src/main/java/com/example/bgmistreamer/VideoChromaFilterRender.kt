package com.example.bgmistreamer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * VideoChromaFilterRender — renders a looping video overlay over the stream
 * with real-time green-screen (chroma key) removal.
 *
 * It removes green from the video overlay only, keeping the background screen
 * capture untouched. Coordinates are mapped top-down to match Android screen orientation.
 */
class VideoChromaFilterRender(
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : BaseFilterRender() {

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
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        uniform sampler2D          uSampler;
        uniform samplerExternalOES uVideo;
        uniform float uSensitive;
        uniform float uScaleX;
        uniform float uScaleY;
        uniform float uOffsetX;
        uniform float uOffsetY;
        uniform int   uHasFrame;

        varying vec2 vTextureCoord;

        void main() {
            vec4 screen = texture2D(uSampler, vTextureCoord);

            if (uHasFrame == 0) {
                gl_FragColor = screen;
                return;
            }

            // Invert Y so that uOffsetY=0 is at TOP of screen and 1.0 is at BOTTOM
            float topDownY = 1.0 - vTextureCoord.y;
            float vx = (vTextureCoord.x - uOffsetX) / max(uScaleX, 0.001);
            float vy = (topDownY - uOffsetY) / max(uScaleY, 0.001);

            // Only draw video within its bounding box
            if (vx < 0.0 || vx > 1.0 || vy < 0.0 || vy > 1.0) {
                gl_FragColor = screen;
                return;
            }

            vec4 video = texture2D(uVideo, vec2(vx, vy));

            // Green difference: how much greener is it than red & blue
            float maxrb = max(video.r, video.b);
            float greenDiff = video.g - maxrb;

            // Sensitivity controls cutoff threshold (higher sensitive = cuts more green)
            float threshold = mix(0.18, 0.04, clamp(uSensitive, 0.0, 1.0));
            float mask = smoothstep(threshold * 0.4, threshold, greenDiff);

            // Despill edge pixels by clamping green to maxrb without turning white
            video.g = min(video.g, maxrb);

            // mask = 1.0 -> green screen (show screen), mask = 0.0 -> video content
            gl_FragColor = mix(video, screen, mask);
        }
    """.trimIndent()

    // ---- GL state -----------------------------------------------------------

    private var program = 0
    private var videoTexId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    @Volatile private var hasFrame = false

    // Attribute handles
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0

    // Uniform handles
    private var uMVPHandle       = 0
    private var uSTHandle        = 0
    private var uSamplerHandle   = 0
    private var uVideoHandle     = 0
    private var uSensitiveHandle = 0
    private var uScaleXHandle    = 0
    private var uScaleYHandle    = 0
    private var uOffsetXHandle   = 0
    private var uOffsetYHandle   = 0
    private var uHasFrameHandle  = 0

    // Full-screen quad (position xy + tex uv)
    private val QUAD_COORDS = floatArrayOf(
        // X,    Y,    U,    V
        -1f,  -1f,  0f,  0f,
         1f,  -1f,  1f,  0f,
        -1f,   1f,  0f,  1f,
         1f,   1f,  1f,  1f
    )
    private lateinit var quadBuffer: FloatBuffer

    // Overlay placement
    @Volatile var overlayScaleX  = 0.5f
    @Volatile var overlayScaleY  = 0.5f
    @Volatile var overlayOffsetX = 0f
    @Volatile var overlayOffsetY = 0f
    @Volatile private var _sensitive = 0.50f

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    override fun initGlFilter(context: Context) {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)

        // Build quad VBO
        quadBuffer = ByteBuffer
            .allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD_COORDS); position(0) }

        // Compile shaders
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        // Get attribute locations
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")

        // Get uniform locations
        uMVPHandle       = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTHandle        = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle   = GLES20.glGetUniformLocation(program, "uSampler")
        uVideoHandle     = GLES20.glGetUniformLocation(program, "uVideo")
        uSensitiveHandle = GLES20.glGetUniformLocation(program, "uSensitive")
        uScaleXHandle    = GLES20.glGetUniformLocation(program, "uScaleX")
        uScaleYHandle    = GLES20.glGetUniformLocation(program, "uScaleY")
        uOffsetXHandle   = GLES20.glGetUniformLocation(program, "uOffsetX")
        uOffsetYHandle   = GLES20.glGetUniformLocation(program, "uOffsetY")
        uHasFrameHandle  = GLES20.glGetUniformLocation(program, "uHasFrame")

        // Create OES texture for MediaPlayer video frames
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        videoTexId = texIds[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // Expose SurfaceTexture so MediaPlayer can render into it
        videoSurfaceTexture = SurfaceTexture(videoTexId).also { st ->
            st.setOnFrameAvailableListener {
                hasFrame = true
            }
            onSurfaceReady(st)
        }
    }

    override fun drawFilter() {
        if (hasFrame) {
            try {
                videoSurfaceTexture?.updateTexImage()
            } catch (e: Exception) {
                // Ignore transient frame availability issues
            }
        }

        GLES20.glUseProgram(program)

        // --- bind camera/stream texture to unit 0 --------------------------
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glUniform1i(uSamplerHandle, 0)

        // --- bind video OES texture to unit 1 ------------------------------
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(uVideoHandle, 1)

        // --- uniforms -------------------------------------------------------
        GLES20.glUniformMatrix4fv(uMVPHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTHandle,  1, false, STMatrix,  0)
        GLES20.glUniform1f(uSensitiveHandle, _sensitive)
        GLES20.glUniform1f(uScaleXHandle,    overlayScaleX)
        GLES20.glUniform1f(uScaleYHandle,    overlayScaleY)
        GLES20.glUniform1f(uOffsetXHandle,   overlayOffsetX)
        GLES20.glUniform1f(uOffsetYHandle,   overlayOffsetY)
        GLES20.glUniform1i(uHasFrameHandle,  if (hasFrame) 1 else 0)

        // --- draw full-screen quad -----------------------------------------
        val stride = 4 * 4 // 4 floats * 4 bytes
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        GLES20.glUseProgram(0)
    }

    override fun release() {
        hasFrame = false
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        if (videoTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(videoTexId), 0)
            videoTexId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    fun setOverlayScale(scaleX: Float, scaleY: Float) {
        overlayScaleX = scaleX
        overlayScaleY = scaleY
    }

    fun setOverlayOffset(offsetX: Float, offsetY: Float) {
        overlayOffsetX = offsetX
        overlayOffsetY = offsetY
    }

    fun setSensitive(value: Float) {
        _sensitive = value.coerceIn(0f, 1f)
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        return GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
        }
}
