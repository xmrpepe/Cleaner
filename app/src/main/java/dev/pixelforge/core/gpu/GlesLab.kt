package dev.pixelforge.core.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import dev.pixelforge.core.nativebridge.NativeLab
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GlesInfo(
    val vendor: String,
    val renderer: String,
    val version: String,
    val glslVersion: String,
    val extensions: List<String>,
    val eglVendor: String,
    val eglVersion: String,
    val eglExtensions: List<String>,
    val limits: Map<String, Int>,
)

data class GlesBenchResult(
    val test: String,
    val value: Double,
    val unit: String,
    val frames: Int,
    val frameMs: List<Double>,
)

/**
 * OpenGL ES probe and offscreen benchmarks. A dedicated EGL context renders into a 1080p
 * pbuffer/FBO with glFinish per frame, so results are not capped by display v-sync.
 */
class GlesSession private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
    val es31: Boolean,
) : AutoCloseable {

    fun info(): GlesInfo {
        fun gi(name: Int): Int { val a = IntArray(1); GLES20.glGetIntegerv(name, a, 0); return a[0] }
        val limits = linkedMapOf(
            "GL_MAX_TEXTURE_SIZE" to gi(GLES20.GL_MAX_TEXTURE_SIZE),
            "GL_MAX_RENDERBUFFER_SIZE" to gi(GLES20.GL_MAX_RENDERBUFFER_SIZE),
            "GL_MAX_3D_TEXTURE_SIZE" to gi(GLES30.GL_MAX_3D_TEXTURE_SIZE),
            "GL_MAX_ARRAY_TEXTURE_LAYERS" to gi(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS),
            "GL_MAX_COLOR_ATTACHMENTS" to gi(GLES30.GL_MAX_COLOR_ATTACHMENTS),
            "GL_MAX_SAMPLES" to gi(GLES30.GL_MAX_SAMPLES),
            "GL_MAX_VERTEX_ATTRIBS" to gi(GLES20.GL_MAX_VERTEX_ATTRIBS),
            "GL_MAX_TEXTURE_IMAGE_UNITS" to gi(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS),
            "GL_MAX_FRAGMENT_UNIFORM_VECTORS" to gi(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS),
        )
        if (es31) {
            limits["GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS"] = gi(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)
            limits["GL_MAX_COMPUTE_SHARED_MEMORY_SIZE"] = gi(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE)
            limits["GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS"] = gi(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)
        }
        val extCount = gi(GLES30.GL_NUM_EXTENSIONS)
        val exts = (0 until extCount).mapNotNull { GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it) }.sorted()
        return GlesInfo(
            vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
            renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
            version = GLES20.glGetString(GLES20.GL_VERSION) ?: "?",
            glslVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "?",
            extensions = exts,
            eglVendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR) ?: "?",
            eglVersion = EGL14.eglQueryString(display, EGL14.EGL_VERSION) ?: "?",
            eglExtensions = (EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: "").split(' ').filter { it.isNotBlank() }.sorted(),
            limits = limits,
        )
    }

    // ---- Benchmarks -------------------------------------------------------------------------

    private val w = 1920
    private val h = 1080

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun withFbo(block: () -> Unit) {
        val fbo = IntArray(1)
        val tex = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        GLES20.glViewport(0, 0, w, h)
        try {
            block()
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(1, tex, 0)
        }
    }

    private inline fun timed(durationMs: Int, frame: (Int) -> Unit): List<Double> {
        val times = ArrayList<Double>()
        val end = System.nanoTime() + durationMs * 1_000_000L
        var i = 0
        // Warm-up frame (shader compilation / driver allocation).
        frame(i++)
        GLES20.glFinish()
        while (System.nanoTime() < end && !cancelled) {
            val t0 = System.nanoTime()
            frame(i++)
            GLES20.glFinish()
            times += (System.nanoTime() - t0) / 1e6
        }
        return times
    }

    @Volatile var cancelled = false

    private val fullscreenVs = """#version 300 es
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }"""

    /** Blended full-screen layers with a light ALU shader → effective Gpixels/s. */
    fun fillRate(durationMs: Int): GlesBenchResult {
        var out: GlesBenchResult? = null
        withFbo {
            val prog = program(fullscreenVs, """#version 300 es
                precision mediump float;
                uniform float uLayer;
                out vec4 color;
                void main() {
                    vec2 uv = gl_FragCoord.xy * 0.001;
                    color = vec4(0.5 + 0.5 * sin(uv.x * 3.0 + uLayer), uv.x, uv.y, 0.05);
                }""")
            GLES20.glUseProgram(prog)
            val loc = GLES20.glGetUniformLocation(prog, "uLayer")
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val layers = 32
            val vao = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])
            val times = timed(durationMs) { f ->
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                for (l in 0 until layers) {
                    GLES20.glUniform1f(loc, l * 0.37f + f * 0.01f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
                }
            }
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES20.glDeleteProgram(prog)
            val gpix = if (times.isEmpty()) 0.0 else w.toDouble() * h * layers * times.size / (times.sum() / 1000) / 1e9
            out = GlesBenchResult("OpenGL ES fill rate", gpix, "Gpix/s", times.size, times)
        }
        return out!!
    }

    /** Instanced small triangles → million triangles/s (vertex + setup throughput). */
    fun geometry(durationMs: Int): GlesBenchResult {
        var out: GlesBenchResult? = null
        withFbo {
            val prog = program("""#version 300 es
                uniform float uTime;
                flat out float vShade;
                void main() {
                    int id = gl_InstanceID;
                    float fx = float(id % 512) / 512.0;
                    float fy = float(id / 512) / 512.0;
                    vec2 base = vec2(fx, fy) * 2.0 - 1.0;
                    vec2 off = vec2(float(gl_VertexID == 1), float(gl_VertexID == 2)) * 0.003;
                    float a = uTime + float(id) * 0.001;
                    mat2 r = mat2(cos(a), -sin(a), sin(a), cos(a));
                    gl_Position = vec4(base + r * off, 0.0, 1.0);
                    vShade = fract(float(id) * 0.618);
                }""", """#version 300 es
                precision mediump float;
                flat in float vShade;
                out vec4 color;
                void main() { color = vec4(vShade, 0.4, 1.0 - vShade, 1.0); }""")
            GLES20.glUseProgram(prog)
            val loc = GLES20.glGetUniformLocation(prog, "uTime")
            val vao = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])
            val instances = 512 * 512
            val times = timed(durationMs) { f ->
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUniform1f(loc, f * 0.02f)
                GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLES, 0, 3, instances)
            }
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES20.glDeleteProgram(prog)
            val mtris = if (times.isEmpty()) 0.0 else instances.toDouble() * times.size / (times.sum() / 1000) / 1e6
            out = GlesBenchResult("OpenGL ES geometry", mtris, "Mtri/s", times.size, times)
        }
        return out!!
    }

    /** 8 bilinear taps per pixel from a 2048² RGBA8 texture → Gtexels/s. */
    fun texture(durationMs: Int): GlesBenchResult {
        var out: GlesBenchResult? = null
        withFbo {
            val size = 2048
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            val data = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until size * size) {
                data.put((i and 0xFF).toByte()); data.put((i shr 8 and 0xFF).toByte()); data.put((i * 7 and 0xFF).toByte()); data.put(-1)
            }
            data.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            val prog = program(fullscreenVs, """#version 300 es
                precision mediump float;
                uniform sampler2D uTex;
                uniform float uT;
                out vec4 color;
                void main() {
                    vec2 uv = gl_FragCoord.xy / vec2(1024.0) + vec2(uT);
                    vec4 acc = vec4(0.0);
                    for (int i = 0; i < 8; i++) acc += texture(uTex, uv + vec2(float(i) * 0.013, float(i) * 0.007));
                    color = acc * 0.125;
                }""")
            GLES20.glUseProgram(prog)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            val tl = GLES20.glGetUniformLocation(prog, "uT")
            val vao = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])
            val passes = 8
            val times = timed(durationMs) { f ->
                for (p in 0 until passes) {
                    GLES20.glUniform1f(tl, f * 0.001f + p * 0.1f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
                }
            }
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES20.glDeleteProgram(prog)
            GLES20.glDeleteTextures(1, tex, 0)
            val gtex = if (times.isEmpty()) 0.0 else w.toDouble() * h * 8 * passes * times.size / (times.sum() / 1000) / 1e9
            out = GlesBenchResult("OpenGL ES texturing", gtex, "Gtexel/s", times.size, times)
        }
        return out!!
    }

    /** GLES 3.1 compute shader FMA throughput → GFLOPS. */
    fun compute(durationMs: Int): GlesBenchResult? {
        if (!es31) return null
        val src = """#version 310 es
            layout(local_size_x = 128) in;
            layout(std430, binding = 0) buffer Data { vec4 data[]; };
            uniform uint uIter;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                vec4 a = data[i]; vec4 m = vec4(0.9999); vec4 c = vec4(0.0001);
                vec4 r0 = a, r1 = a + 1.0, r2 = a + 2.0, r3 = a + 3.0;
                for (uint k = 0u; k < uIter; ++k) {
                    r0 = r0 * m + c; r1 = r1 * m + c; r2 = r2 * m + c; r3 = r3 * m + c;
                }
                data[i] = r0 + r1 + r2 + r3;
            }"""
        val shader = compile(GLES31.GL_COMPUTE_SHADER, src)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader)
        GLES20.glLinkProgram(prog)
        GLES20.glUseProgram(prog)
        val groups = 2048
        val n = groups * 128
        val ssbo = IntArray(1)
        GLES20.glGenBuffers(1, ssbo, 0)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo[0])
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, n * 16, null, GLES30.GL_DYNAMIC_COPY)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo[0])
        val iterLoc = GLES20.glGetUniformLocation(prog, "uIter")
        val iters = 512
        GLES30.glUniform1ui(iterLoc, iters)
        val times = timed(durationMs) { GLES31.glDispatchCompute(groups, 1, 1) }
        GLES20.glDeleteBuffers(1, ssbo, 0)
        GLES20.glDeleteProgram(prog)
        val flopsPerDispatch = n.toDouble() * iters * 4 * 4 * 2
        val gflops = if (times.isEmpty()) 0.0 else flopsPerDispatch * times.size / (times.sum() / 1000) / 1e9
        return GlesBenchResult("OpenGL ES compute", gflops, "GFLOPS", times.size, times)
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    companion object {
        /** Creates an ES 3.x context bound to the calling thread. Must be used on one thread. */
        fun create(): GlesSession? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null
            val config = configs[0]
            var es31 = true
            var ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 3, 0x30FB, 1, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) {
                es31 = false
                ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 3, EGL14.EGL_NONE), 0)
            }
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val surf = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            if (es31 && !(version.contains("3.1") || version.contains("3.2"))) es31 = false
            return GlesSession(display, ctx, surf, es31)
        }
    }
}

/** Parsed Vulkan capability report from the native probe. */
object VulkanInfo {
    fun probe() = NativeLab.vulkanProbe()
}
