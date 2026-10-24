package com.ban.foodintelligence.pos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.Intent
import com.ban.foodintelligence.pos.ui.RedRecallLockActivity
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * ============================================================================
 * BAN Food Intelligence - AVATAR ERP Edge AI Engine
 * Author: Nuri (Principal AI/Android Edge & Security Architect)
 * ============================================================================
 *
 * BUILD CONFIGURATION NOTE (build.gradle.kts):
 * Ensure the following dependency is added to `mobile-kotlin/build.gradle.kts`:
 * ```kotlin
 * dependencies {
 *     implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
 * }
 * ```
 *
 * SECURITY & ARCHITECTURE RATIONALE:
 * Executes 100% offline Level-2 Stacked Meta-Learner inference on the POS edge
 * terminal via ONNX Runtime (`nuri_edge_model.onnx`). By eliminating external
 * cloud inference calls during retail lot check-in, the terminal is immune to
 * API Man-in-the-Middle (MITM) response tampering and network blackouts.
 */

/**
 * Thrown when telemetry sensors report biologically or physically impossible
 * Out-of-Distribution (OOD) cold-chain parameters (e.g., sensor spoofing,
 * thermal runaway > 100°C, negative transit durations, or corrupted payloads).
 *
 * Catching this exception at the POS checkout layer immediately invokes
 * [RedRecallLockActivity] to quarantine the batch and lock terminal hardware.
 */
class AnomalyDetectedException(
    val anomalyCode: String,
    val offendingMetric: String,
    val observedValue: Float,
    override val message: String
) : RuntimeException(message)

class OfflineYieldPredictor(
    private val appContext: Context,
    private val modelAssetName: String = DEFAULT_ONNX_ASSET_NAME,
    private val autoTriggerLockdownOnAnomaly: Boolean = false
) : Closeable {

    companion object {
        const val DEFAULT_ONNX_ASSET_NAME = "nuri_edge_model.onnx"
        const val ONNX_INPUT_TENSOR_NAME = "float_input"

        // Strict Biological & Thermodynamic OOD Boundaries for Cold-Chain Meat Logistics
        const val MIN_BIOLOGICAL_TEMP_C = -40.0f
        const val MAX_BIOLOGICAL_TEMP_C = 100.0f
        const val MIN_TRANSIT_HOURS = 0.0f
        const val MAX_TRANSIT_HOURS = 720.0f // 30 days max physical cold-chain horizon
        const val MIN_HUMIDITY_PCT = 0.0f
        const val MAX_HUMIDITY_PCT = 100.0f
        const val MIN_PACK_INTEGRITY = 0.0f
        const val MAX_PACK_INTEGRITY = 1.0f
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        ortSession = loadModelSessionFromAssets(modelAssetName)
    }

    /**
     * Reads `nuri_edge_model.onnx` bytes from Android `assets/` and initializes
     * an isolated, thread-safe [OrtSession] optimized for ARMv8 NPU/CPU execution.
     */
    private fun loadModelSessionFromAssets(assetFileName: String): OrtSession {
        val modelBytes = appContext.assets.open(assetFileName).use { inputStream ->
            inputStream.readBytes()
        }
        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
        }
        return ortEnv.createSession(modelBytes, sessionOptions)
    }

    /**
     * Performs Out-of-Distribution (OOD) biological & physical sanity validation
     * followed by local ONNX Runtime tensor inference to predict retail retention yield (%).
     *
     * @param transitHours Cold-chain transit duration in hours.
     * @param tempC Ambient storage/transit temperature in degrees Celsius.
     * @param humidity Relative storage humidity percentage (0.0 - 100.0).
     * @param packIntegrity Vacuum/MAP packaging barrier score (0.0 - 1.0).
     * @return Predicted `retail_retention_yield_pct` clamped within [0.0f, 100.0f].
     * @throws AnomalyDetectedException if inputs violate biological/physical laws.
     */
    @Throws(AnomalyDetectedException::class)
    fun predictYield(
        transitHours: Float,
        tempC: Float,
        humidity: Float,
        packIntegrity: Float
    ): Float {
        // 1. Out-of-Distribution (OOD) Guardrail Check
        validateBiophysicalBounds(
            transitHours = transitHours,
            tempC = tempC,
            humidity = humidity,
            packIntegrity = packIntegrity
        )

        // 2. Construct [1, 4] Contiguous FloatBuffer for ONNX Input Tensor
        val inputFeatures = floatArrayOf(transitHours, tempC, humidity, packIntegrity)
        val floatBuffer = FloatBuffer.wrap(inputFeatures)
        val tensorShape = longArrayOf(1L, 4L)

        val inputName = if (ortSession.inputNames.contains(ONNX_INPUT_TENSOR_NAME)) {
            ONNX_INPUT_TENSOR_NAME
        } else {
            ortSession.inputNames.iterator().next()
        }

        // 3. Execute Zero-Latency Edge Inference inside Managed Tensor Scope
        OnnxTensor.createTensor(ortEnv, floatBuffer, tensorShape).use { inputTensor ->
            val inputs = mapOf(inputName to inputTensor)
            ortSession.run(inputs).use { results ->
                val rawOutput = results[0].value
                val rawPrediction = extractScalarFloat(rawOutput)

                if (rawPrediction.isNaN() || rawPrediction.isInfinite()) {
                    val ex = AnomalyDetectedException(
                        anomalyCode = "RCL-AI-NAN-OUTPUT",
                        offendingMetric = "onnx_output_tensor",
                        observedValue = Float.NaN,
                        message = "CRITICAL OOD FAULT: ONNX model produced non-finite output ($rawPrediction)."
                    )
                    handleAnomalyLockdown(ex)
                    throw ex
                }

                // Clamp within physical mass conservation bounds [0.0%, 100.0%]
                return rawPrediction.coerceIn(0.0f, 100.0f)
            }
        }
    }

    /**
     * Enforces strict biophysical and thermodynamic OOD rules before tensor allocation.
     * Any violation raises [AnomalyDetectedException] to engage [RedRecallLockActivity].
     */
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
                    "[$MIN_BIOLOGICAL_TEMP_C°C, $MAX_BIOLOGICAL_TEMP_C°C]. Potential sensor tampering or severe thermal hazard!"
            )
            handleAnomalyLockdown(ex)
            throw ex
        }

        if (transitHours < MIN_TRANSIT_HOURS || transitHours > MAX_TRANSIT_HOURS) {
            val ex = AnomalyDetectedException(
                anomalyCode = "RCL-OOD-TRANSIT-HORIZON",
                offendingMetric = "transit_time_hours",
                observedValue = transitHours,
                message = "CHRONOLOGICAL OOD ANOMALY: Transit duration ($transitHours h) violates physical bounds " +
                    "[$MIN_TRANSIT_HOURS h, $MAX_TRANSIT_HOURS h]."
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
                message = "PACKAGING OOD ANOMALY: Packaging integrity score ($packIntegrity) outside valid interval (0.0, 1.0]."
            )
            handleAnomalyLockdown(ex)
            throw ex
        }
    }

    /**
     * Safely unwraps scalar float prediction from ONNX output structures
     * (`Array<FloatArray>`, `FloatArray`, `Array<DoubleArray>`, or `DoubleArray`).
     */
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
     * Launches Nuri's [RedRecallLockActivity] hardware lockdown screen when
     * an Out-of-Distribution anomaly or telemetry spoofing attack is detected.
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
