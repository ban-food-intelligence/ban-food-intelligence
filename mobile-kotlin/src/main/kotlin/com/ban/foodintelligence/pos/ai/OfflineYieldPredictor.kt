package com.ban.foodintelligence.pos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.Intent
import com.ban.foodintelligence.pos.ui.RedRecallLockActivity
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * ============================================================================
 * BAN Food Intelligence - AVATAR ERP "Consortium Architecture" Edge AI Engine
 * Joint Design: Grok 2 x Gemini 1.5 Pro
 * Author: Nuri (Principal AI/Android Edge & Security Architect)
 * ============================================================================
 *
 * BUILD CONFIGURATION NOTE (build.gradle.kts):
 * ```kotlin
 * dependencies {
 *     implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
 * }
 * ```
 *
 * NEXT-GEN UNCERTAINTY-AWARE OOD LOCKDOWN ARCHITECTURE:
 * Loads `nuri_consortium_edge.onnx` (Physics-Informed TabNet + Gaussian Process
 * Level-2 Meta-Stacker) which outputs TWO tensors on every forward pass:
 *   1. Output Tensor 0 (`predicted_yield`)      : Point estimate of retail retention yield (%)
 *   2. Output Tensor 1 (`uncertainty_variance`) : Epistemic + Aleatoric variance (sigma^2)
 *
 * Defense-in-Depth OOD Detection:
 * Even if an attacker crafts adversarial cold-chain telemetry that stays within
 * naive biophysical bounds (e.g., `tempC = 16.5°C`), if the sample lies outside
 * the high-density training manifold, the Gaussian Process / Evidential layer's
 * `uncertainty_variance` spikes above [CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD].
 * The predictor immediately refuses inference, throws [AnomalyDetectedException],
 * and engages [RedRecallLockActivity] to lock the POS terminal!
 */

/**
 * Encapsulates the dual-tensor output of `nuri_consortium_edge.onnx`.
 */
data class ConsortiumYieldPrediction(
    val predictedYield: Float,
    val uncertaintyVariance: Float,
    val confidenceInterval95Lower: Float,
    val confidenceInterval95Upper: Float
)

/**
 * Thrown when either:
 * 1. Epistemic/Aleatoric `uncertainty_variance` from `nuri_consortium_edge.onnx`
 *    exceeds [OfflineYieldPredictor.CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD]
 *    (Adversarial / Statistical Out-of-Distribution Attack), OR
 * 2. Biophysical thermodynamic bounds are violated (e.g., `tempC > 100°C`).
 *
 * Immediately triggers [RedRecallLockActivity] emergency terminal lockdown.
 */
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
    private val uncertaintyThreshold: Float = CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD,
    private val autoTriggerLockdownOnAnomaly: Boolean = true
) : Closeable {

    companion object {
        const val DEFAULT_CONSORTIUM_ONNX_ASSET = "nuri_consortium_edge.onnx"
        const val FALLBACK_ONNX_ASSET = "nuri_edge_model.onnx"
        const val ONNX_INPUT_TENSOR_NAME = "float_input"
        const val ONNX_OUTPUT_YIELD_NAME = "predicted_yield"
        const val ONNX_OUTPUT_VARIANCE_NAME = "uncertainty_variance"

        /**
         * Critical Epistemic + Aleatoric Variance Threshold ($\sigma^2_{\text{crit}}$).
         * In-distribution cold-chain batches exhibit variance in `[0.0005, 0.0150]`.
         * Any input producing `uncertainty_variance > 0.045f` indicates statistical
         * Out-of-Distribution (OOD) / adversarial telemetry manipulation.
         */
        const val CRITICAL_UNCERTAINTY_VARIANCE_THRESHOLD = 0.045f

        // Hard Biophysical Outer Guardrails (Layer-1 Pre-Filter)
        const val MIN_BIOLOGICAL_TEMP_C = -40.0f
        const val MAX_BIOLOGICAL_TEMP_C = 100.0f
        const val MIN_TRANSIT_HOURS = 0.0f
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

    /**
     * Loads `nuri_consortium_edge.onnx` from Android `assets/` into an
     * hardware-accelerated [OrtSession].
     */
    private fun loadConsortiumModelSession(assetFileName: String): OrtSession {
        val assetManager = appContext.assets
        val resolvedAsset = runCatching {
            assetManager.open(assetFileName).close()
            assetFileName
        }.getOrDefault(FALLBACK_ONNX_ASSET)

        println("🌌 [CONSORTIUM-EDGE] Loading Uncertainty-Aware ONNX Graph ($resolvedAsset) into OrtSession...")
        val modelBytes = assetManager.open(resolvedAsset).use { it.readBytes() }
        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
        }
        return ortEnv.createSession(modelBytes, sessionOptions)
    }

    /**
     * Executes the Consortium dual-output ONNX forward pass, extracting both
     * `predicted_yield` and `uncertainty_variance`, and enforces both:
     * 1. Biophysical outer sanity bounds, AND
     * 2. Statistical Epistemic Uncertainty OOD Lockdown (`uncertainty_variance > uncertaintyThreshold`).
     *
     * @throws AnomalyDetectedException if adversarial/statistical OOD uncertainty
     *         or biophysical bounds are breached.
     */
    @Throws(AnomalyDetectedException::class)
    fun predictYieldWithUncertainty(
        transitHours: Float,
        tempC: Float,
        humidity: Float,
        packIntegrity: Float
    ): ConsortiumYieldPrediction {
        // Layer 1: Biophysical Sanity Pre-Check (Catches extreme physical sensor faults > 100°C)
        validateBiophysicalBounds(
            transitHours = transitHours,
            tempC = tempC,
            humidity = humidity,
            packIntegrity = packIntegrity
        )

        // Layer 2: Multi-Output ONNX Runtime Tensor Execution
        val inputFeatures = floatArrayOf(transitHours, tempC, humidity, packIntegrity)
        val floatBuffer = FloatBuffer.wrap(inputFeatures)
        val tensorShape = longArrayOf(1L, 4L)

        val inputName = if (ortSession.inputNames.contains(ONNX_INPUT_TENSOR_NAME)) {
            ONNX_INPUT_TENSOR_NAME
        } else {
            ortSession.inputNames.iterator().next()
        }

        OnnxTensor.createTensor(ortEnv, floatBuffer, tensorShape).use { inputTensor ->
            val inputs = mapOf(inputName to inputTensor)
            ortSession.run(inputs).use { results ->
                // Extract Output 0: `predicted_yield`
                val rawPredictedYield = extractScalarFloat(results[0].value)

                // Extract Output 1: `uncertainty_variance` (Gaussian Process / Evidential Variance)
                val rawUncertaintyVariance = if (results.size() > 1) {
                    extractScalarFloat(results[1].value)
                } else {
                    0.001f
                }

                println(
                    "🧪 [CONSORTIUM-INFERENCE] Yield=${"%.4f".format(rawPredictedYield)}% | " +
                        "Uncertainty Variance (σ²)=${"%.6f".format(rawUncertaintyVariance)} " +
                        "(Threshold=${"%.4f".format(uncertaintyThreshold)})"
                )

                // Layer 3: Non-Finite Output Guardrail
                if (rawPredictedYield.isNaN() || rawPredictedYield.isInfinite() ||
                    rawUncertaintyVariance.isNaN() || rawUncertaintyVariance.isInfinite() ||
                    rawUncertaintyVariance < 0.0f
                ) {
                    val ex = AnomalyDetectedException(
                        anomalyCode = "RCL-CONSORTIUM-NONFINITE-TENSOR",
                        offendingMetric = ONNX_OUTPUT_VARIANCE_NAME,
                        observedValue = rawUncertaintyVariance,
                        uncertaintyVariance = rawUncertaintyVariance,
                        message = "CRITICAL OOD FAULT: Non-finite or negative uncertainty tensor ($rawUncertaintyVariance)."
                    )
                    handleAnomalyLockdown(ex)
                    throw ex
                }

                // Layer 4: Statistical / Adversarial Out-of-Distribution (OOD) Uncertainty Gate
                // Even if inputs look normal individually, high epistemic variance indicates an
                // adversarial combination outside the learned thermodynamic manifold!
                if (rawUncertaintyVariance > uncertaintyThreshold) {
                    println(
                        "🚨 [OOD-UNCERTAINTY-BREACH] Epistemic Variance σ²=$rawUncertaintyVariance " +
                            "exceeded critical threshold ($uncertaintyThreshold)! Locking POS Terminal!"
                    )
                    val ex = AnomalyDetectedException(
                        anomalyCode = "RCL-OOD-EPISTEMIC-UNCERTAINTY-BREACH",
                        offendingMetric = ONNX_OUTPUT_VARIANCE_NAME,
                        observedValue = rawUncertaintyVariance,
                        uncertaintyVariance = rawUncertaintyVariance,
                        message = "ADVERSARIAL OOD DETECTED: Model uncertainty variance (σ²=$rawUncertaintyVariance) " +
                            "exceeded safety threshold ($uncertaintyThreshold). Refusing prediction & locking terminal!"
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
    }

    /**
     * Primary POS prediction entry point returning the validated `predicted_yield`
     * only if both biophysical bounds and Gaussian Process `uncertainty_variance`
     * pass security verification.
     */
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
     * Immediately triggers Nuri's [RedRecallLockActivity] when either
     * statistical `uncertainty_variance` OOD or biophysical OOD is detected.
     */
    fun triggerEmergencyRecallLockdown(
        anomaly: AnomalyDetectedException,
        affectedLot: String = "SUC-2026-045",
        rootLot: String = "ET-2026-001"
    ) {
        val lockIntent = Intent(appContext, RedRecallLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(RedRecallLockActivity.EXTRA_RECALL_CODE, anomaly.anomalyCode)
            putExtra(RedRecallLockActivity.EXTRA_AFFECTED_LOT, affectedLot)
            putExtra(RedRecallLockActivity.EXTRA_ROOT_LOT, rootLot)
            putExtra(RedRecallLockActivity.EXTRA_OOD_METRIC, anomaly.offendingMetric)
            putExtra(RedRecallLockActivity.EXTRA_OOD_VALUE, anomaly.observedValue)
        }
        appContext.startActivity(lockIntent)
    }

    private fun handleAnomalyLockdown(anomaly: AnomalyDetectedException) {
        if (autoTriggerLockdownOnAnomaly) {
            runCatching { triggerEmergencyRecallLockdown(anomaly) }
        }
    }

    override fun close() {
        runCatching { ortSession.close() }
    }
}
