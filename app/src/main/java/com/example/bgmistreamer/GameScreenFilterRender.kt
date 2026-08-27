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
 * Eliminates the letterbox black bars created by Android MediaProjection when
 * capturing an ultra-wide mobile screen (e.g. 2.17:1) into a 16:9 stream.
 *
 * It extracts the real game pixels from the centered input texture and positions
 * the game screen according to user scale and offset (default: top-aligned, leaving
 * bottom space for overlays).
 */
class GameScreenFilterRender(
    private var phoneRatio: Float = 2.17f,
    private val streamRatio: Float = 16f / 9f,
    var scale: Float = 1.0f,
    var offsetX: Float = 0.0f,
    var offsetY: Float = 0.0f
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
        precision mediump float;

        uniform sampler2D uSampler;
        uniform float uContentHeight;
        uniform float uTopBar;
        uniform float uScale;
        uniform float uOffsetX;
        uniform float uOffsetY;

        varying vec2 vTextureCoord;

        void main() {
            // Convert GL coordinate (0 at bottom) to Top-Down coordinate (0 at top)
            float topDownY = 1.0 - vTextureCoord.y;
            float x = vTextureCoord.x;

            // Target destination rectangle in top-down space
            float outW = uScale;
            float outH = uScale * uContentHeight;
            float minX = uOffsetX;
            float maxX = uOffsetX + outW;
            float minY = uOffsetY;
            float maxY = uOffsetY + outH;

            if (x >= minX && x <= maxX && topDownY >= minY && topDownY <= maxY) {
                // Normalized coordinate inside destination (0.0 to 1.0)
                float normX = (x - minX) / outW;
                float normY = (topDownY - minY) / outH;

                // Map to source texture where real game pixels reside (ignoring Android's black bars)
                float srcTopDownY = uTopBar + normY * uContentHeight;
                float srcGLY = 1.0 - srcTopDownY;

                gl_FragColor = texture2D(uSampler, vec2(normX, srcGLY));
            } else {
                // Outside game area: clean solid black canvas for overlays
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
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
    private var uScaleHandle = 0
    private var uOffsetXHandle = 0
    private var uOffsetYHandle = 0

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    fun setPhoneAspectRatio(ratio: Float) {
        phoneRatio = if (ratio > 0f) ratio else 2.17f
    }

    fun updateLayout(newScale: Float, newOffsetX: Float, newOffsetY: Float) {
        scale = newScale.coerceIn(0.1f, 1.0f)
        offsetX = newOffsetX.coerceIn(0.0f, 1.0f)
        offsetY = newOffsetY.coerceIn(0.0f, 1.0f)
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
        uScaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        uOffsetXHandle = GLES20.glGetUniformLocation(program, "uOffsetX")
        uOffsetYHandle = GLES20.glGetUniformLocation(program, "uOffsetY")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        // Bind source texture from VirtualDisplay to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glUniform1i(uSamplerHandle, 0)

        // Calculate game screen content metrics
        // In 16:9 (1.7778) frame, a 2.17 phone occupies:
        // contentHeight = 1.7778 / 2.17 = ~0.8193
        val contentHeight = (streamRatio / phoneRatio.coerceAtLeast(streamRatio)).coerceIn(0.1f, 1.0f)
        val topBar = (1.0f - contentHeight) / 2.0f

        GLES20.glUniformMatrix4fv(uMVPHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uContentHeightHandle, contentHeight)
        GLES20.glUniform1f(uTopBarHandle, topBar)
        GLES20.glUniform1f(uScaleHandle, scale)
        GLES20.glUniform1f(uOffsetXHandle, offsetX)
        GLES20.glUniform1f(uOffsetYHandle, offsetY)

        val stride = 4 * 4
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, stride, quadBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)
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
