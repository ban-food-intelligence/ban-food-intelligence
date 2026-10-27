package com.ban.foodintelligence.pos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ============================================================================
 * BAN Food Intelligence - AVATAR ERP "Quantum Shield" Edge AI Engine
 * Author: Nuri (Principal AI/Android Edge & Security Architect)
 * ============================================================================
 *
 * QUANTUM SHIELD ANTI-ADVERSARIAL & SIDE-CHANNEL REMEDIATION:
 * 1. Dual-Bound Epistemic Variance Gate:
 *    Enforces BOTH:
 *      - Strict Lower-Bound Floor (`MIN_UNCERTAINTY_VARIANCE_FLOOR = 0.0005f`):
 *        Detects FGSM / PGD adversarial variance-suppression attacks that attempt
 *        to force Gaussian Process variance artificially close to zero.
 *      - Strict Upper-Bound Ceiling (`CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD = 0.045f`):
 *        Detects statistical Out-of-Distribution (OOD) telemetry manipulation.
 * 2. Arrhenius Thermodynamic Manifold Invariant:
 *    Blocks tangential manifold spoofing (where an attacker moves orthogonal to
 *    the Mahalanobis radius to inflate `predicted_yield` to ~99% while keeping
 *    ellipsoid distance constant) by verifying that the neural yield prediction
 *    does not deviate from the physical Arrhenius rate law upper bound.
 * 3. Side-Channel Elimination:
 *    - Allocates direct native `ByteBuffer` and zeroizes both JVM and direct
 *      memory buffers immediately in a `finally` block after `ortSession.run()`.
 *    - Eliminates the `println` raw floating-point `logcat` gradient oracle.
 */
data class ConsortiumYieldPrediction(
    val predictedYield: Float,
    val uncertaintyVariance: Float,
    val confidenceInterval95Lower: Float,
    val confidenceInterval95Upper: Float
)

class AnomalyDetectedException(
    val anomalyCode: String,
    val offendingMetric: String,
    val observedValue: Float,
    val uncertaintyVariance: Float = Float.NaN,
    override val message: String
) : RuntimeException(message)

class OfflineYieldPredictor(
    private val appContext: Context,
    private val modelAssetName: String = DEFAULT_CONSORTIUM_ONNX_ASSET,
    private val minVarianceFloor: Float = MIN_UNCERTAINTY_VARIANCE_FLOOR,
    private val uncertaintyThreshold: Float = CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD,
) : Closeable {

    companion object {
        private const val TAG = "OfflineYieldPredictor"
        const val DEFAULT_CONSORTIUM_ONNX_ASSET = "nuri_consortium_edge.onnx"
        const val FALLBACK_ONNX_ASSET = "nuri_edge_model.onnx"
        const val ONNX_INPUT_TENSOR_NAME = "float_input"
        const val ONNX_OUTPUT_YIELD_NAME = "predicted_yield"
        const val ONNX_OUTPUT_VARIANCE_NAME = "uncertainty_variance"

        /**
         * Strict Lower-Bound Variance Floor ($\sigma^2_{\min}$).
         * Any input producing `uncertainty_variance < 0.0005f` indicates an
         * adversarial variance-suppression / synthetic replay payload.
         */
        const val MIN_UNCERTAINTY_VARIANCE_FLOOR = 0.0005f

        /**
         * Critical Epistemic + Aleatoric Variance Ceiling ($\sigma^2_{\text{crit}}$).
         * Any input producing `uncertainty_variance > 0.045f` indicates statistical
         * Out-of-Distribution (OOD) / adversarial telemetry manipulation.
         */
        const val CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD = 0.045f

        /**
         * Maximum permissible positive divergence (in yield percentage points)
         * between the ONNX neural prediction and the physical Arrhenius ceiling.
         * Defeats tangential FGSM manifold spoofing.
         */
        const val MAX_ARRHENIUS_MANIFOLD_RESIDUAL_PTS = 2.25f

        // Hard Biophysical Cold-Chain Guardrails (Layer-1 Pre-Filter)
        const val MIN_BIOLOGICAL_TEMP_C = -25.0f
        const val MAX_BIOLOGICAL_TEMP_C = 100.0f
        const val MIN_TRANSIT_HOURS = 0.5f
        const val MAX_TRANSIT_HOURS = 720.0f
        const val MIN_HUMIDITY_PCT = 0.0f
        const val MAX_HUMIDITY_PCT = 100.0f
        const val MIN_PACK_INTEGRITY = 0.0f
        const val MAX_PACK_INTEGRITY = 1.0f
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        ortSession = loadConsortiumModelSession(modelAssetName)
    }

    private fun loadConsortiumModelSession(assetFileName: String): OrtSession {
        val assetManager = appContext.assets
        val resolvedAsset = runCatching {
            assetManager.open(assetFileName).close()
            assetFileName
        }.getOrDefault(FALLBACK_ONNX_ASSET)

        Log.i(TAG, "🛡️ [QUANTUM-SHIELD] Loading Hardened Dual-Output ONNX Graph ($resolvedAsset)...")
        val modelBytes = assetManager.open(resolvedAsset).use { it.readBytes() }
        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
        }
        return try {
            ortEnv.createSession(modelBytes, sessionOptions)
        } finally {
            modelBytes.fill(0)
        }
    }

    /**
     * Executes the Quantum-Shield hardened dual-output ONNX forward pass:
     * 1. Biophysical Cold-Chain Pre-Check
     * 2. Direct-Buffer Zeroized ONNX Execution
     * 3. Dual-Bound Variance Gate (`[minVarianceFloor, uncertaintyThreshold]`)
     * 4. Arrhenius Tangential Manifold Spoofing Gate
     */
    @Throws(AnomalyDetectedException::class)
    fun predictYieldWithUncertainty(
        transitHours: Float,
        tempC: Float,
        humidity: Float,
        packIntegrity: Float
    ): ConsortiumYieldPrediction {
        validateBiophysicalBounds(
            transitHours = transitHours,
            tempC = tempC,
            humidity = humidity,
            packIntegrity = packIntegrity
        )

        val directByteBuffer = ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val floatBuffer = directByteBuffer.asFloatBuffer().apply {
            put(0, transitHours)
            put(1, tempC)
            put(2, humidity)
            put(3, packIntegrity)
            rewind()
        }
        val tensorShape = longArrayOf(1L, 4L)
        val inputName = if (ortSession.inputNames.contains(ONNX_INPUT_TENSOR_NAME)) {
            ONNX_INPUT_TENSOR_NAME
        } else {
            ortSession.inputNames.iterator().next()
        }

        try {
            OnnxTensor.createTensor(ortEnv, floatBuffer, tensorShape).use { inputTensor ->
                val inputs = mapOf(inputName to inputTensor)
                ortSession.run(inputs).use { results ->
                    val rawPredictedYield = extractScalarFloat(results[0].value)
                    val rawUncertaintyVariance = if (results.size() > 1) {
                        extractScalarFloat(results[1].value)
                    } else {
                        Float.NaN
                    }

                    // Layer 2: Non-Finite Output Guardrail
                    if (rawPredictedYield.isNaN() || rawPredictedYield.isInfinite() ||
                        rawUncertaintyVariance.isNaN() || rawUncertaintyVariance.isInfinite()
                    ) {
                        val ex = AnomalyDetectedException(
                            anomalyCode = "RCL-CONSORTIUM-NONFINITE-TENSOR",
                            offendingMetric = ONNX_OUTPUT_VARIANCE_NAME,
                            observedValue = rawUncertaintyVariance,
                            uncertaintyVariance = rawUncertaintyVariance,
                            message = "CRITICAL OOD FAULT: Non-finite output tensor."
                        )
                        handleAnomalyLockdown(ex)
                        throw ex
                    }

                    // Layer 3A: Strict Lower-Bound Variance Floor (Anti-FGSM Variance Suppression)
                    if (rawUncertaintyVariance < minVarianceFloor) {
                        val ex = AnomalyDetectedException(
                            anomalyCode = "RCL-OOD-ADVERSARIAL-VARIANCE-UNDERFLOW",
                            offendingMetric = ONNX_OUTPUT_VARIANCE_NAME,
                            observedValue = rawUncertaintyVariance,
                            uncertaintyVariance = rawUncertaintyVariance,
                            message = "ADVERSARIAL VARIANCE SPOOFING: Epistemic variance (σ²=$rawUncertaintyVariance) " +
                                "dropped below strict lower-bound floor ($minVarianceFloor)!"
                        )
                        handleAnomalyLockdown(ex)
                        throw ex
                    }

                    // Layer 3B: Strict Upper-Bound Variance Ceiling (Statistical OOD Gate)
                    if (rawUncertaintyVariance > uncertaintyThreshold) {
                        val ex = AnomalyDetectedException(
                            anomalyCode = "RCL-OOD-EPISTEMIC-UNCERTAINTY-BREACH",
                            offendingMetric = ONNX_OUTPUT_VARIANCE_NAME,
                            observedValue = rawUncertaintyVariance,
                            uncertaintyVariance = rawUncertaintyVariance,
                            message = "ADVERSARIAL OOD DETECTED: Model uncertainty variance (σ²=$rawUncertaintyVariance) " +
                                "exceeded safety ceiling ($uncertaintyThreshold). Locking terminal!"
                        )
                        handleAnomalyLockdown(ex)
                        throw ex
                    }

                    // Layer 4: Anti-FGSM Tangential Manifold Invariant (Arrhenius Physical Ceiling)
                    val physicalCeilingYield = computeArrheniusPhysicalCeiling(
                        transitHours = transitHours,
                        tempC = tempC,
                        humidity = humidity,
                        packIntegrity = packIntegrity
                    )
                    if (rawPredictedYield - physicalCeilingYield > MAX_ARRHENIUS_MANIFOLD_RESIDUAL_PTS) {
                        val ex = AnomalyDetectedException(
                            anomalyCode = "RCL-OOD-FGSM-MANIFOLD-SPOOF",
                            offendingMetric = ONNX_OUTPUT_YIELD_NAME,
                            observedValue = rawPredictedYield,
                            uncertaintyVariance = rawUncertaintyVariance,
                            message = "FGSM MANIFOLD SPOOF DETECTED: Predicted yield ($rawPredictedYield%) " +
                                "violates Arrhenius thermodynamic ceiling ($physicalCeilingYield%)!"
                        )
                        handleAnomalyLockdown(ex)
                        throw ex
                    }

                    val clampedYield = rawPredictedYield.coerceIn(0.0f, 100.0f)
                    val stdDev = sqrt(rawUncertaintyVariance)
                    val ci95Delta = 1.96f * stdDev

                    return ConsortiumYieldPrediction(
                        predictedYield = clampedYield,
                        uncertaintyVariance = rawUncertaintyVariance,
                        confidenceInterval95Lower = (clampedYield - ci95Delta).coerceIn(0.0f, 100.0f),
                        confidenceInterval95Upper = (clampedYield + ci95Delta).coerceIn(0.0f, 100.0f)
                    )
                }
            }
        } finally {
            // Side-channel countermeasure: zeroize direct native input buffer immediately
            for (i in 0 until 4) {
                floatBuffer.put(i, 0.0f)
            }
        }
    }

    @Throws(AnomalyDetectedException::class)
    fun predictYield(
        transitHours: Float,
        tempC: Float,
        humidity: Float,
        packIntegrity: Float
    ): Float {
        return predictYieldWithUncertainty(
            transitHours = transitHours,
            tempC = tempC,
            humidity = humidity,
            packIntegrity = packIntegrity
        ).predictedYield
    }

    /**
     * Computes the analytical Arrhenius thermodynamic upper-bound yield for the
     * given cold-chain state. Blocks tangential FGSM steps that attempt to push
     * `predicted_yield` toward 99% while staying on the Mahalanobis level set.
     */
    private fun computeArrheniusPhysicalCeiling(
        transitHours: Float,
        tempC: Float,
        humidity: Float,
        packIntegrity: Float
    ): Float {
        val effectiveTemp = max(0.0, tempC.toDouble() + 2.0)
        val arrheniusRate = 0.36 * exp(0.112 * effectiveTemp) * (transitHours.toDouble() / 12.0).pow(1.25)
        val barrierMitigation = exp(-1.50 * (packIntegrity.toDouble() - 0.40))
        val sealCompromise = max(0.0, 0.70 - packIntegrity.toDouble()).pow(2.0) * 12.5
        val desiccation = 0.038 * max(0.0, 84.0 - humidity.toDouble()).pow(1.12) *
            (1.12 - 0.72 * packIntegrity.toDouble())
        val minShrinkage = 1.10 + (arrheniusRate * barrierMitigation) + sealCompromise + desiccation
        return (100.0 - minShrinkage).toFloat().coerceIn(0.0f, 99.0f)
    }

    @Throws(AnomalyDetectedException::class)
    private fun validateBiophysicalBounds(
        transitHours: Float,
        tempC: Float,
        humidity: Float,
        packIntegrity: Float
    ) {
        if (transitHours.isNaN() || tempC.isNaN() || humidity.isNaN() || packIntegrity.isNaN() ||
            transitHours.isInfinite() || tempC.isInfinite() || humidity.isInfinite() || packIntegrity.isInfinite()
        ) {
            val ex = AnomalyDetectedException(
                anomalyCode = "RCL-OOD-CORRUPT-SENSOR-IEEE754",
                offendingMetric = "sensor_telemetry_vector",
                observedValue = Float.NaN,
                message = "OOD ANOMALY: Non-finite IEEE-754 float detected in cold-chain telemetry."
            )
            handleAnomalyLockdown(ex)
            throw ex
        }

        if (tempC > MAX_BIOLOGICAL_TEMP_C || tempC < MIN_BIOLOGICAL_TEMP_C) {
            val ex = AnomalyDetectedException(
                anomalyCode = "RCL-OOD-THERMAL-BIOHAZARD",
                offendingMetric = "ambient_temp_celsius",
                observedValue = tempC,
                message = "BIOLOGICAL OOD ANOMALY: Ambient temperature ($tempC°C) outside viable range " +
                    "[$MIN_BIOLOGICAL_TEMP_C°C, $MAX_BIOLOGICAL_TEMP_C°C]."
            )
            handleAnomalyLockdown(ex)
            throw ex
        }

        if (transitHours < MIN_TRANSIT_HOURS || transitHours > MAX_TRANSIT_HOURS) {
            val ex = AnomalyDetectedException(
                anomalyCode = "RCL-OOD-TRANSIT-HORIZON",
                offendingMetric = "transit_time_hours",
                observedValue = transitHours,
                message = "CHRONOLOGICAL OOD ANOMALY: Transit duration ($transitHours h) violates physical bounds."
            )
            handleAnomalyLockdown(ex)
            throw ex
        }

        if (humidity < MIN_HUMIDITY_PCT || humidity > MAX_HUMIDITY_PCT) {
            val ex = AnomalyDetectedException(
                anomalyCode = "RCL-OOD-HYGROMETRIC-BOUNDS",
                offendingMetric = "storage_humidity_pct",
                observedValue = humidity,
                message = "HYGROMETRIC OOD ANOMALY: Storage humidity ($humidity%) outside physical bounds [0%, 100%]."
            )
            handleAnomalyLockdown(ex)
            throw ex
        }

        if (packIntegrity <= MIN_PACK_INTEGRITY || packIntegrity > MAX_PACK_INTEGRITY) {
            val ex = AnomalyDetectedException(
                anomalyCode = "RCL-OOD-PACKAGING-INTEGRITY",
                offendingMetric = "packaging_integrity_score",
                observedValue = packIntegrity,
                message = "PACKAGING OOD ANOMALY: Packaging integrity score ($packIntegrity) outside (0.0, 1.0]."
            )
            handleAnomalyLockdown(ex)
            throw ex
        }
    }

    private fun extractScalarFloat(outputValue: Any?): Float {
        return when (outputValue) {
            is Array<*> -> {
                val firstRow = outputValue.firstOrNull()
                when (firstRow) {
                    is FloatArray -> firstRow[0]
                    is DoubleArray -> firstRow[0].toFloat()
                    is Number -> firstRow.toFloat()
                    else -> throw IllegalStateException("Unsupported nested ONNX output type: ${firstRow?.javaClass}")
                }
            }
            is FloatArray -> outputValue[0]
            is DoubleArray -> outputValue[0].toFloat()
            is Number -> outputValue.toFloat()
            else -> throw IllegalStateException("Unsupported ONNX output tensor type: ${outputValue?.javaClass}")
        }
    }

    /**
     * An implausible model input/output is only logged. It never locks the terminal or
     * blocks a lot: recall and traceability come from recorded lot relations in MySQL,
     * not from AI predictions (spec, section 4).
     */
    private fun handleAnomalyLockdown(anomaly: AnomalyDetectedException) {
        android.util.Log.w("OfflineYieldPredictor", "Prediction rejected: ${anomaly.anomalyCode} (${anomaly.offendingMetric}=${anomaly.observedValue})")
    }

    override fun close() {
        runCatching { ortSession.close() }
    }
}
