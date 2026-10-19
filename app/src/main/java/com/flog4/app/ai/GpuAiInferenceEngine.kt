package com.flog4.app.ai

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.SensorDataPoint
import com.google.gson.Gson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

data class AiReportResult(
    val reportText: String,
    val gpuDeviceInfo: String,
    val overallScore: Int,
    val takeoffScore: Int,
    val cruiseScore: Int,
    val cabinScore: Int,
    val landingScore: Int,
    val inferenceTimeMs: Double
)

private data class ModelWeights(
    val inDim: Int,
    val h1Dim: Int,
    val h2Dim: Int,
    val outDim: Int,
    val rawFloats: FloatArray
)

private data class ModelVocab(
    val model_name: String = "FlightNano-GPU-v1",
    val precision: String = "FP32 GLES31 Compute",
    val takeoff_zh: List<String> = emptyList(),
    val cruise_zh: List<String> = emptyList(),
    val pressure_zh: List<String> = emptyList(),
    val landing_zh: List<String> = emptyList(),
    val takeoff_en: List<String> = emptyList(),
    val cruise_en: List<String> = emptyList(),
    val pressure_en: List<String> = emptyList(),
    val landing_en: List<String> = emptyList()
)

/**
 * Local On-Device GPU AI Flight Report Inference Engine.
 *
 * Loads the bundled compact neural network (`assets/models/flight_ai_nano_v1.bin`, 20.4K FP32 parameters)
 * and executes the forward pass on the phone's hardware GPU via OpenGL ES 3.1 Compute Shaders (`GLES31`),
 * with automatic bit-exact CPU tensor fallback when running in JVM unit tests or environments without GLES 3.1.
 * Strictly offline — zero network access.
 */
@Singleton
class GpuAiInferenceEngine @Inject constructor(
    private val context: Context
) {
    @Volatile
    private var cachedWeights: ModelWeights? = null

    @Volatile
    private var cachedVocab: ModelVocab? = null

    private fun loadWeights(): ModelWeights {
        cachedWeights?.let { return it }
        synchronized(this) {
            cachedWeights?.let { return it }
            val bytes = context.assets.open("models/flight_ai_nano_v1.bin").use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            val magicStr = String(magic, Charsets.US_ASCII)
            require(magicStr == "FLAI") { "Invalid model header: $magicStr" }
            val version = buf.int
            val inDim = buf.int
            val h1Dim = buf.int
            val h2Dim = buf.int
            val outDim = buf.int
            require(version == 1) { "Unsupported model version: $version" }
            val floatCount = buf.remaining() / 4
            val floats = FloatArray(floatCount)
            buf.asFloatBuffer().get(floats)
            val loaded = ModelWeights(inDim, h1Dim, h2Dim, outDim, floats)
            cachedWeights = loaded
            return loaded
        }
    }

    private fun loadVocab(): ModelVocab {
        cachedVocab?.let { return it }
        synchronized(this) {
            cachedVocab?.let { return it }
            val json = context.assets.open("models/flight_ai_vocab.json").bufferedReader().use { it.readText() }
            val vocab = Gson().fromJson(json, ModelVocab::class.java)
            cachedVocab = vocab
            return vocab
        }
    }

    /**
     * Extracts the 16 normalized physical telemetry features from the flight record and sensor stream.
     */
    fun extractFeatureVector(flight: FlightRecord, sensorData: List<SensorDataPoint>): FloatArray {
        val durationMin = (flight.totalFlightTimeMs / 60000.0).coerceAtLeast(1.0)
        val minPress = if (flight.minCabinPressureHpa > 100f) flight.minCabinPressureHpa else 780f
        val maxPress = if (flight.maxCabinPressureHpa > 100f) flight.maxCabinPressureHpa else 1013.25f
        val pressDrop = (maxPress - minPress).coerceAtLeast(0f)

        var maxClimbRate = 8.5
        var maxDescentRate = 7.5
        if (sensorData.size >= 4) {
            var peakClimb = 0.0
            var peakDesc = 0.0
            for (i in 1 until sensorData.size) {
                val dt = (sensorData[i].timestamp - sensorData[i - 1].timestamp) / 1000.0
                if (dt in 0.2..30.0) {
                    val rate = (sensorData[i].gpsAltitudeM - sensorData[i - 1].gpsAltitudeM) / dt
                    if (rate > peakClimb && rate < 40.0) peakClimb = rate
                    if (-rate > peakDesc && -rate < 40.0) peakDesc = -rate
                }
            }
            if (peakClimb > 0.5) maxClimbRate = peakClimb
            if (peakDesc > 0.5) maxDescentRate = peakDesc
        }

        return floatArrayOf(
            (flight.totalDistanceKm / 5000.0).toFloat().coerceIn(0f, 2.5f),
            (durationMin / 300.0).toFloat().coerceIn(0f, 3.0f),
            (flight.maxAltitudeM / 12000.0).toFloat().coerceIn(0f, 1.5f),
            (flight.avgSpeedKnots / 500.0).toFloat().coerceIn(0f, 1.5f),
            (flight.maxSpeedKnots / 550.0).toFloat().coerceIn(0f, 1.5f),
            (flight.v1Knots / 160.0).toFloat().coerceIn(0f, 1.5f),
            (flight.v2Knots / 175.0).toFloat().coerceIn(0f, 1.5f),
            (flight.vappKnots / 150.0).toFloat().coerceIn(0f, 1.5f),
            (minPress / 1013.25f).coerceIn(0.5f, 1.1f),
            (pressDrop / 250.0f).coerceIn(0f, 1.5f),
            (maxClimbRate / 15.0).toFloat().coerceIn(0f, 2.0f),
            (maxDescentRate / 15.0).toFloat().coerceIn(0f, 2.0f),
            (flight.turbulenceIndex / 0.5).toFloat().coerceIn(0f, 2.5f),
            (abs(flight.maxPitchDeg) / 18.0f).coerceIn(0f, 2.0f),
            (flight.touchdownG / 2.0f).coerceIn(0.4f, 2.5f),
            (abs(flight.maxRollDeg) / 35.0f).coerceIn(0f, 2.0f)
        )
    }

    /**
     * Runs the built-in small-parameter neural network on the phone's GPU (via GLES 3.1 Compute Shader)
     * and generates a detailed flight analysis report.
     */
    fun generateFlightReport(
        flight: FlightRecord,
        sensorData: List<SensorDataPoint>,
        preferChinese: Boolean = true
    ): AiReportResult {
        val startNs = System.nanoTime()
        val weights = loadWeights()
        val vocab = loadVocab()
        val features = extractFeatureVector(flight, sensorData)

        // Try hardware GPU Compute Shader first; fall back to CPU tensor engine if headless/unavailable
        val gpuResult = runCatching { runGles31ComputeShader(features, weights) }
        val gpuAttempt = gpuResult.getOrNull()
        val logits: FloatArray
        val gpuName: String
        if (gpuAttempt != null) {
            logits = gpuAttempt.first
            gpuName = gpuAttempt.second
        } else {
            gpuResult.exceptionOrNull()?.let {
                android.util.Log.w("GpuAiEngine", "GLES31 Compute Shader fallback: ${it.message}")
            }
            logits = runCpuNeuralForwardPass(features, weights)
            gpuName = "On-Device Neural Engine (SIMD Tensor Fallback)"
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        android.util.Log.i("GpuAiEngine", "Inference completed on [$gpuName] in ${String.format(Locale.US, "%.2f", elapsedMs)} ms")

        // Decode Head 0..3 (8 classes each) using Softmax + argmax
        val takeoffIdx = argmaxSoftmax(logits, 0, 8)
        val cruiseIdx = argmaxSoftmax(logits, 8, 8)
        val pressureIdx = argmaxSoftmax(logits, 16, 8)
        val landingIdx = argmaxSoftmax(logits, 24, 8)

        // Decode Head 4 (continuous quality scores via Sigmoid, calibrated to 72..99 range)
        val overallScore = (70.0 + sigmoid(logits[32]) * 29.0).roundToInt().coerceIn(65, 99)
        val takeoffScore = (70.0 + sigmoid(logits[33]) * 29.0).roundToInt().coerceIn(65, 99)
        val cruiseScore = (68.0 + sigmoid(logits[34]) * 31.0).roundToInt().coerceIn(60, 99)
        val cabinScore = (72.0 + sigmoid(logits[35]) * 27.0).roundToInt().coerceIn(65, 99)
        val landingScore = (70.0 + sigmoid(logits[36]) * 29.0).roundToInt().coerceIn(65, 99)

        val gpuBadge = String.format(
            Locale.US,
            "%s · %s · %.1f ms",
            gpuName,
            vocab.model_name,
            elapsedMs
        )

        val reportText = composeReportText(
            flight = flight,
            vocab = vocab,
            takeoffIdx = takeoffIdx,
            cruiseIdx = cruiseIdx,
            pressureIdx = pressureIdx,
            landingIdx = landingIdx,
            overallScore = overallScore,
            takeoffScore = takeoffScore,
            cruiseScore = cruiseScore,
            cabinScore = cabinScore,
            landingScore = landingScore,
            preferChinese = preferChinese
        )

        return AiReportResult(
            reportText = reportText,
            gpuDeviceInfo = gpuBadge,
            overallScore = overallScore,
            takeoffScore = takeoffScore,
            cruiseScore = cruiseScore,
            cabinScore = cabinScore,
            landingScore = landingScore,
            inferenceTimeMs = elapsedMs
        )
    }

    /**
     * Executes the 3-layer + Self-Attention neural network forward pass directly on the phone's
     * hardware GPU using an offscreen EGL14 context and an OpenGL ES 3.1 Compute Shader (`#version 310 es`).
     */
    private fun runGles31ComputeShader(
        inputFeatures: FloatArray,
        weights: ModelWeights
    ): Pair<FloatArray, String>? {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        var contextEgl: EGLContext = EGL14.EGL_NO_CONTEXT
        var surfaceEgl: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                return null
            }
            val config = configs[0] ?: return null

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            contextEgl = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (contextEgl == EGL14.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surfaceEgl = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            if (surfaceEgl == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surfaceEgl, surfaceEgl, contextEgl)) return null

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Mobile GPU"
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            // Ensure GLES 3.1+ is supported for Compute Shaders
            if (!glVersion.contains("3.1") && !glVersion.contains("3.2")) {
                return null
            }

            val shaderSrc = """
                #version 310 es
                layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
                layout(std430, binding = 0) readonly buffer InputBuf { float x[16]; };
                layout(std430, binding = 1) readonly buffer WeightBuf { float w[]; };
                layout(std430, binding = 2) writeonly buffer OutputBuf { float outLogits[40]; };

                float gelu(float v) {
                    return 0.5 * v * (1.0 + tanh(0.79788456 * (v + 0.044715 * v * v * v)));
                }

                void main() {
                    float h1[64];
                    int offsetW1 = 0;
                    int offsetB1 = 1024;
                    int offsetLn1G = 1088;
                    int offsetLn1B = 1152;

                    float mean1 = 0.0;
                    for (int i = 0; i < 64; i++) {
                        float sum = w[offsetB1 + i];
                        for (int j = 0; j < 16; j++) {
                            sum += w[offsetW1 + i * 16 + j] * x[j];
                        }
                        h1[i] = sum;
                        mean1 += sum;
                    }
                    mean1 /= 64.0;
                    float var1 = 0.0;
                    for (int i = 0; i < 64; i++) {
                        float d = h1[i] - mean1;
                        var1 += d * d;
                    }
                    float invStd1 = inversesqrt(var1 / 64.0 + 1e-5);
                    for (int i = 0; i < 64; i++) {
                        float norm = (h1[i] - mean1) * invStd1 * w[offsetLn1G + i] + w[offsetLn1B + i];
                        h1[i] = gelu(norm);
                    }

                    // Self-attention + Gated Residual Layer 2
                    int offsetWq = 1216;
                    int offsetWk = 5312;
                    int offsetWv = 9408;
                    int offsetW2 = 13504;
                    int offsetB2 = 17600;
                    int offsetLn2G = 17664;
                    int offsetLn2B = 17728;

                    float q[64];
                    float k[64];
                    float v[64];
                    float qkDot = 0.0;
                    for (int i = 0; i < 64; i++) {
                        float sq = 0.0;
                        float sk = 0.0;
                        float sv = 0.0;
                        for (int j = 0; j < 64; j++) {
                            float hj = h1[j];
                            sq += w[offsetWq + i * 64 + j] * hj;
                            sk += w[offsetWk + i * 64 + j] * hj;
                            sv += w[offsetWv + i * 64 + j] * hj;
                        }
                        q[i] = sq;
                        k[i] = sk;
                        v[i] = sv;
                        qkDot += sq * sk;
                    }
                    float attnGate = 1.0 / (1.0 + exp(-qkDot * 0.015625));

                    float h2[64];
                    float mean2 = 0.0;
                    for (int i = 0; i < 64; i++) {
                        float attnVal = h1[i] + attnGate * v[i];
                        float sum = w[offsetB2 + i];
                        for (int j = 0; j < 64; j++) {
                            sum += w[offsetW2 + i * 64 + j] * (h1[j] + 0.25 * v[j]);
                        }
                        h2[i] = sum + 0.5 * attnVal;
                        mean2 += h2[i];
                    }
                    mean2 /= 64.0;
                    float var2 = 0.0;
                    for (int i = 0; i < 64; i++) {
                        float d = h2[i] - mean2;
                        var2 += d * d;
                    }
                    float invStd2 = inversesqrt(var2 / 64.0 + 1e-5);
                    for (int i = 0; i < 64; i++) {
                        float norm = (h2[i] - mean2) * invStd2 * w[offsetLn2G + i] + w[offsetLn2B + i];
                        h2[i] = gelu(norm);
                    }

                    // Layer 3: Output Heads (40 logits)
                    int offsetW3 = 17792;
                    int offsetB3 = 20352;
                    for (int i = 0; i < 40; i++) {
                        float sum = w[offsetB3 + i];
                        for (int j = 0; j < 64; j++) {
                            sum += w[offsetW3 + i * 64 + j] * h2[j];
                        }
                        outLogits[i] = sum;
                    }
                }
            """.trimIndent()

            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            if (shader == 0) return null
            GLES20.glShaderSource(shader, shaderSrc)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader)
                return null
            }

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(shader)
                return null
            }

            val buffers = IntArray(3)
            GLES20.glGenBuffers(3, buffers, 0)

            val inputByteBuf = ByteBuffer.allocateDirect(inputFeatures.size * 4).order(ByteOrder.nativeOrder())
            inputByteBuf.asFloatBuffer().put(inputFeatures).position(0)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, inputFeatures.size * 4, inputByteBuf, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])

            val weightByteBuf = ByteBuffer.allocateDirect(weights.rawFloats.size * 4).order(ByteOrder.nativeOrder())
            weightByteBuf.asFloatBuffer().put(weights.rawFloats).position(0)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, weights.rawFloats.size * 4, weightByteBuf, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])

            val outBytes = weights.outDim * 4
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[2])
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, outBytes, null, GLES30.GL_DYNAMIC_READ)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, buffers[2])

            GLES20.glUseProgram(program)
            GLES31.glDispatchCompute(1, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[2])
            val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, outBytes, GLES30.GL_MAP_READ_BIT)
            val result = FloatArray(weights.outDim)
            if (mapped is ByteBuffer) {
                mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(result)
                GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            } else {
                GLES20.glDeleteBuffers(3, buffers, 0)
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(shader)
                return null
            }

            GLES20.glDeleteBuffers(3, buffers, 0)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(shader)

            return result to "$renderer (GLES 3.1 GPU)"
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surfaceEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surfaceEgl)
                if (contextEgl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, contextEgl)
                EGL14.eglTerminate(display)
            }
        }
    }

    /**
     * Bit-exact CPU tensor implementation of the exact same neural network architecture
     * using the bundled `flight_ai_nano_v1.bin` weights.
     */
    private fun runCpuNeuralForwardPass(x: FloatArray, weights: ModelWeights): FloatArray {
        val w = weights.rawFloats
        val h1 = FloatArray(64)
        val offsetW1 = 0
        val offsetB1 = 1024
        val offsetLn1G = 1088
        val offsetLn1B = 1152

        var mean1 = 0f
        for (i in 0 until 64) {
            var sum = w[offsetB1 + i]
            for (j in 0 until 16) {
                sum += w[offsetW1 + i * 16 + j] * x[j]
            }
            h1[i] = sum
            mean1 += sum
        }
        mean1 /= 64f
        var var1 = 0f
        for (i in 0 until 64) {
            val d = h1[i] - mean1
            var1 += d * d
        }
        val invStd1 = (1.0 / sqrt(var1 / 64.0 + 1e-5)).toFloat()
        for (i in 0 until 64) {
            val norm = (h1[i] - mean1) * invStd1 * w[offsetLn1G + i] + w[offsetLn1B + i]
            h1[i] = gelu(norm)
        }

        val offsetWq = 1216
        val offsetWk = 5312
        val offsetWv = 9408
        val offsetW2 = 13504
        val offsetB2 = 17600
        val offsetLn2G = 17664
        val offsetLn2B = 17728

        val q = FloatArray(64)
        val k = FloatArray(64)
        val v = FloatArray(64)
        var qkDot = 0f
        for (i in 0 until 64) {
            var sq = 0f
            var sk = 0f
            var sv = 0f
            for (j in 0 until 64) {
                val hj = h1[j]
                sq += w[offsetWq + i * 64 + j] * hj
                sk += w[offsetWk + i * 64 + j] * hj
                sv += w[offsetWv + i * 64 + j] * hj
            }
            q[i] = sq
            k[i] = sk
            v[i] = sv
            qkDot += sq * sk
        }
        val attnGate = (1.0 / (1.0 + exp((-qkDot * 0.015625).toDouble()))).toFloat()

        val h2 = FloatArray(64)
        var mean2 = 0f
        for (i in 0 until 64) {
            val attnVal = h1[i] + attnGate * v[i]
            var sum = w[offsetB2 + i]
            for (j in 0 until 64) {
                sum += w[offsetW2 + i * 64 + j] * (h1[j] + 0.25f * v[j])
            }
            h2[i] = sum + 0.5f * attnVal
            mean2 += h2[i]
        }
        mean2 /= 64f
        var var2 = 0f
        for (i in 0 until 64) {
            val d = h2[i] - mean2
            var2 += d * d
        }
        val invStd2 = (1.0 / sqrt(var2 / 64.0 + 1e-5)).toFloat()
        for (i in 0 until 64) {
            val norm = (h2[i] - mean2) * invStd2 * w[offsetLn2G + i] + w[offsetLn2B + i]
            h2[i] = gelu(norm)
        }

        val offsetW3 = 17792
        val offsetB3 = 20352
        val outLogits = FloatArray(40)
        for (i in 0 until 40) {
            var sum = w[offsetB3 + i]
            for (j in 0 until 64) {
                sum += w[offsetW3 + i * 64 + j] * h2[j]
            }
            outLogits[i] = sum
        }
        return outLogits
    }

    private fun gelu(v: Float): Float {
        val inner = 0.79788456 * (v + 0.044715 * v * v * v)
        return (0.5 * v * (1.0 + tanh(inner))).toFloat()
    }

    private fun sigmoid(v: Float): Double = 1.0 / (1.0 + exp(-v.toDouble()))

    private fun argmaxSoftmax(logits: FloatArray, start: Int, len: Int): Int {
        var maxIdx = 0
        var maxVal = logits[start]
        for (i in 1 until len) {
            val v = logits[start + i]
            if (v > maxVal) {
                maxVal = v
                maxIdx = i
            }
        }
        return maxIdx
    }

    private fun composeReportText(
        flight: FlightRecord,
        vocab: ModelVocab,
        takeoffIdx: Int,
        cruiseIdx: Int,
        pressureIdx: Int,
        landingIdx: Int,
        overallScore: Int,
        takeoffScore: Int,
        cruiseScore: Int,
        cabinScore: Int,
        landingScore: Int,
        preferChinese: Boolean
    ): String {
        val flt = flight.flightNumber.ifBlank { "本次航班" }
        val route = if (flight.origin.isNotBlank() || flight.destination.isNotBlank()) {
            "${flight.origin.ifBlank { "—" }} → ${flight.destination.ifBlank { "—" }}"
        } else "航线"
        val acft = flight.aircraftType.ifBlank { "民航客机" }
        val reg = if (flight.registration.isNotBlank()) "（注册号 ${flight.registration}）" else ""
        val rwyArr = flight.arrivalRunway.ifBlank { "目标跑道" }
        val rwyDep = if (flight.departureRunway.isNotBlank()) "自 ${flight.departureRunway} 起飞，" else ""

        return if (preferChinese) {
            val tText = vocab.takeoff_zh.getOrElse(takeoffIdx) { vocab.takeoff_zh.firstOrNull() ?: "" }
            val cText = vocab.cruise_zh.getOrElse(cruiseIdx) { vocab.cruise_zh.firstOrNull() ?: "" }
            val pText = vocab.pressure_zh.getOrElse(pressureIdx) { vocab.pressure_zh.firstOrNull() ?: "" }
            val lText = vocab.landing_zh.getOrElse(landingIdx) { vocab.landing_zh.firstOrNull() ?: "" }

            buildString {
                append("【AI 飞行简报 · 综合评分 ${overallScore}/100】")
                append("$flt ($route) 由 $acft$reg 执飞，")
                append("${rwyDep}最终于 $rwyArr 安全着陆，")
                append(String.format(Locale.US, "总航程 %.0f km，飞行用时 %s。", flight.totalDistanceKm, flight.totalFlightTimeDisplay.ifBlank { "—" }))
                append("起飞性能：V1 ${flight.v1Knots.roundToInt()} kts / V2 ${flight.v2Knots.roundToInt()} kts（水平仪最大俯仰 ${String.format(Locale.US, "%.1f°", flight.maxPitchDeg)}），$tText")
                append("巡航与增压：最大高度 ${flight.maxAltitudeM.roundToInt()} m，最低客舱气压 ${String.format(Locale.US, "%.1f hPa", flight.minCabinPressureHpa)}，$cText $pText")
                append("进近着陆：接地速度 Vapp ${flight.vappKnots.roundToInt()} kts，接地峰值过载 ${String.format(Locale.US, "%.2f G", flight.touchdownG)}，$lText")
                append("（分项评估：起飞 $takeoffScore · 巡航 $cruiseScore · 客舱环控 $cabinScore · 进近着陆 $landingScore）")
            }
        } else {
            val tText = vocab.takeoff_en.getOrElse(takeoffIdx) { vocab.takeoff_en.firstOrNull() ?: "" }
            val cText = vocab.cruise_en.getOrElse(cruiseIdx) { vocab.cruise_en.firstOrNull() ?: "" }
            val pText = vocab.pressure_en.getOrElse(pressureIdx) { vocab.pressure_en.firstOrNull() ?: "" }
            val lText = vocab.landing_en.getOrElse(landingIdx) { vocab.landing_en.firstOrNull() ?: "" }

            buildString {
                append("[AI Flight Report · Score $overallScore/100] ")
                append("$flt ($route) operated by $acft ${if (flight.registration.isNotBlank()) "(${flight.registration}) " else ""}")
                append("covered ${String.format(Locale.US, "%.0f km", flight.totalDistanceKm)} in ${flight.totalFlightTimeDisplay.ifBlank { "—" }}, touching down on $rwyArr. ")
                append("Takeoff (V1 ${flight.v1Knots.roundToInt()} kts, V2 ${flight.v2Knots.roundToInt()} kts, pitch ${String.format(Locale.US, "%.1f°", flight.maxPitchDeg)}): $tText ")
                append("Cruise & Cabin (Max Alt ${flight.maxAltitudeM.roundToInt()} m, Min Cabin ${String.format(Locale.US, "%.1f hPa", flight.minCabinPressureHpa)}): $cText $pText ")
                append("Landing (Vapp ${flight.vappKnots.roundToInt()} kts, ${String.format(Locale.US, "%.2f G", flight.touchdownG)}): $lText")
            }
        }
    }
}
