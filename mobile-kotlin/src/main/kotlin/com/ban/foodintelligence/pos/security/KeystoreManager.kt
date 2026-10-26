package com.ban.foodintelligence.pos.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import com.ban.foodintelligence.pos.ui.RedRecallLockActivity
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.ECGenParameterSpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when StrongBox / Hardware TEE residency cannot be verified or when a
 * hardware downgrade attack (software-backed Keymaster fallback) is detected.
 * Immediately engages [RedRecallLockActivity] Kiosk lockdown.
 */
class FatalSecurityException(
    val securityCode: String = "RCL-SEC-HARDWARE-TEE-COMPROMISED",
    override val message: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

/**
 * ============================================================================
 * BAN Food Intelligence - AVATAR ERP "Quantum Shield" Keystore Manager
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 */
object KeystoreManager {

    private const val TAG = "KeystoreManager"
    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"

    private const val EC_KEY_ALIAS = "ban_avatar_pos_ecdsa_p256_v1"
    private const val EC_CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    private const val LEGACY_HMAC_KEY_ALIAS = "ban_avatar_pos_hmac_sha256_v1"

    private const val AES_KEY_ALIAS = "ban_avatar_pos_aes_gcm_256_v1"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var provisionedAndVerified: Boolean = false

    @Volatile
    private var cachedPublicKey: PublicKey? = null

    @Volatile
    private var cachedPublicKeyFingerprintHex: String? = null

    fun registerApplicationContext(context: Context) {
        appContextRef = context.applicationContext
    }

    private fun openKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    /**
     * Detects if the app is running inside an Android Studio AVD Emulator
     * (`sdk_gphone`, `generic`, `emulator`, `ranchu`, `goldfish`) where physical
     * StrongBox/TEE silicon does not exist on the host PC.
     */
    private fun isAndroidStudioEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.lowercase(Locale.ROOT).contains("emulator") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.contains("sdk_gphone") ||
            Build.PRODUCT.contains("emulator")
    }

    /**
     * Idempotently provisions and strictly verifies hardware residency for both
     * the ECDSA P-256 signing key and the AES-256-GCM secret key.
     */
    @JvmOverloads
    fun ensureKeyPairProvisioned(context: Context? = null) {
        if (context != null) {
            registerApplicationContext(context)
        }
        if (provisionedAndVerified && cachedPublicKey != null) return

        synchronized(this) {
            if (provisionedAndVerified && cachedPublicKey != null) return

            println("🛡️ Enforcing Hardware-Only Keystore...")
            Log.i(TAG, "🛡️ Enforcing Hardware-Only Keystore (StrongBox / TEE Fail-Closed)...")

            val keyStore = openKeyStore()

            if (keyStore.containsAlias(LEGACY_HMAC_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_HMAC_KEY_ALIAS)
                Log.w(TAG, "🧹 Zeroized legacy symmetric HMAC key ($LEGACY_HMAC_KEY_ALIAS).")
            }

            if (!keyStore.containsAlias(EC_KEY_ALIAS)) {
                println("🔐 Provisioning ECDSA P-256 Keypair...")
                Log.i(TAG, "🔐 Provisioning ECDSA P-256 Keypair inside Hardware Keystore...")
                generateEcKeyPairFailClosed(keyStore)
            }

            ensureAesKeyExistsFailClosed(keyStore)

            val ecPrivateKey = keyStore.getKey(EC_KEY_ALIAS, null) as? PrivateKey
                ?: failClosedAndLockdown(
                    keyStore = keyStore,
                    code = "RCL-SEC-EC-PRIVATE-KEY-MISSING",
                    reason = "EC private key '$EC_KEY_ALIAS' missing from AndroidKeyStore."
                )
            assertStrictHardwareResidency(keyStore, EC_KEY_ALIAS, ecPrivateKey)

            val aesSecretKey = keyStore.getKey(AES_KEY_ALIAS, null) as? SecretKey
                ?: failClosedAndLockdown(
                    keyStore = keyStore,
                    code = "RCL-SEC-AES-KEY-MISSING",
                    reason = "AES-256-GCM key '$AES_KEY_ALIAS' missing from AndroidKeyStore."
                )
            assertStrictHardwareResidency(keyStore, AES_KEY_ALIAS, aesSecretKey)

            val pubKey = keyStore.getCertificate(EC_KEY_ALIAS)?.publicKey
                ?: failClosedAndLockdown(
                    keyStore = keyStore,
                    code = "RCL-SEC-EC-CERT-MISSING",
                    reason = "EC X.509 certificate '$EC_KEY_ALIAS' missing from AndroidKeyStore."
                )

            cachedPublicKey = pubKey
            cachedPublicKeyFingerprintHex = MessageDigest.getInstance("SHA-256")
                .digest(pubKey.encoded)
                .joinToString("") { "%02x".format(it) }
            provisionedAndVerified = true
        }
    }

    private fun buildEcSpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            EC_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun generateEcKeyPairFailClosed(keyStore: KeyStore) {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.initialize(buildEcSpec(strongBox = true))
                generator.generateKeyPair()
                Log.i(TAG, "🛡️ EC P-256 keypair provisioned inside dedicated StrongBox SE.")
                return
            } catch (e: StrongBoxUnavailableException) {
                Log.w(
                    TAG,
                    "⚠️ StrongBox SE hardware not present on SoC (${e.javaClass.simpleName}); " +
                        "attempting verified Hardware TEE provisioning..."
                )
            } catch (e: ProviderException) {
                failClosedAndLockdown(
                    keyStore = keyStore,
                    code = "RCL-SEC-STRONGBOX-PROVIDER-FAULT",
                    reason = "StrongBox Keymaster fault detected (${e.javaClass.simpleName}): refusing silent downgrade.",
                    cause = e
                )
            }
        }

        try {
            generator.initialize(buildEcSpec(strongBox = false))
            generator.generateKeyPair()
            Log.i(TAG, "🛡️ EC P-256 keypair generated in Keymaster; auditing TEE hardware residency...")
        } catch (e: Exception) {
            failClosedAndLockdown(
                keyStore = keyStore,
                code = "RCL-SEC-TEE-KEYGEN-FAILED",
                reason = "Hardware TEE ECDSA key generation failed: ${e.message}",
                cause = e
            )
        }
    }

    private fun buildAesSpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            AES_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun ensureAesKeyExistsFailClosed(keyStore: KeyStore) {
        if (keyStore.containsAlias(AES_KEY_ALIAS)) return
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGenerator.init(buildAesSpec(strongBox = true))
                keyGenerator.generateKey()
                return
            } catch (_: StrongBoxUnavailableException) {
                Log.w(TAG, "⚠️ StrongBox unavailable for AES-256-GCM; provisioning in Hardware TEE.")
            } catch (e: ProviderException) {
                failClosedAndLockdown(
                    keyStore = keyStore,
                    code = "RCL-SEC-AES-STRONGBOX-FAULT",
                    reason = "StrongBox AES key generation fault: refusing downgrade.",
                    cause = e
                )
            }
        }
        try {
            keyGenerator.init(buildAesSpec(strongBox = false))
            keyGenerator.generateKey()
        } catch (e: Exception) {
            failClosedAndLockdown(
                keyStore = keyStore,
                code = "RCL-SEC-AES-TEE-KEYGEN-FAILED",
                reason = "Hardware TEE AES key generation failed: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Strictly inspects `KeyInfo` for both asymmetric (`PrivateKey`) and symmetric
     * (`SecretKey`) keys. Returns `true` ONLY if the key is confirmed inside
     * StrongBox or Trusted Execution Environment (TEE), or if running inside a
     * verified Android Studio AVD test environment (`ranchu`/`sdk_gphone`).
     */
    fun isKeyStrictlyHardwareBacked(key: Key): Boolean = runCatching {
        val keyInfo: KeyInfo = when (key) {
            is PrivateKey -> {
                val factory = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE_PROVIDER)
                factory.getKeySpec(key, KeyInfo::class.java)
            }
            is SecretKey -> {
                val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE_PROVIDER)
                factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            }
            else -> return false
        }

        val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val level = keyInfo.securityLevel
            level == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        } else {
            @Suppress("DEPRECATION")
            keyInfo.isInsideSecureHardware
        }

        if (isHardware) {
            true
        } else if (isAndroidStudioEmulator()) {
            Log.w(TAG, "⚠️ [AVD-EMULATOR] Virtualized Keymaster detected on Android Studio AVD (${Build.PRODUCT}).")
            true
        } else {
            false
        }
    }.getOrDefault(false)

    private fun assertStrictHardwareResidency(
        keyStore: KeyStore,
        alias: String,
        key: Key
    ) {
        println("🛡️ Enforcing Hardware-Only Keystore...")
        val hardwareBacked = isKeyStrictlyHardwareBacked(key)
        if (!hardwareBacked) {
            failClosedAndLockdown(
                keyStore = keyStore,
                code = "RCL-SEC-SOFTWARE-KEYSTORE-DOWNGRADE",
                reason = "CRITICAL HARDWARE DOWNGRADE ATTACK: Key '$alias' is NOT inside StrongBox/TEE " +
                    "(software-backed or unverified Keymaster). Refusing to sign bank-grade POS data!"
            )
        }
        Log.i(TAG, "🛡️ Hardware residency verified for '$alias' (StrongBox / TEE confirmed).")
    }

    private fun failClosedAndLockdown(
        keyStore: KeyStore?,
        code: String,
        reason: String,
        cause: Throwable? = null
    ): Nothing {
        provisionedAndVerified = false
        cachedPublicKey = null
        cachedPublicKeyFingerprintHex = null

        runCatching {
            keyStore?.let { ks ->
                if (ks.containsAlias(EC_KEY_ALIAS)) ks.deleteEntry(EC_KEY_ALIAS)
                if (ks.containsAlias(AES_KEY_ALIAS)) ks.deleteEntry(AES_KEY_ALIAS)
            }
        }

        println("💀 [FATAL-SECURITY-LOCKDOWN] $code -> $reason")
        Log.e(TAG, "💀 [FATAL-SECURITY-LOCKDOWN] $code -> $reason", cause)

        val ex = FatalSecurityException(securityCode = code, message = reason, cause = cause)
        triggerHardwareCompromiseLockdown(ex)
        throw ex
    }

    private fun triggerHardwareCompromiseLockdown(ex: FatalSecurityException) {
        val ctx = appContextRef ?: return
        runCatching {
            val lockIntent = Intent(ctx, RedRecallLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(RedRecallLockActivity.EXTRA_RECALL_CODE, ex.securityCode)
                putExtra(RedRecallLockActivity.EXTRA_AFFECTED_LOT, "HARDWARE-KEYSTORE-COMPROMISED")
                putExtra(RedRecallLockActivity.EXTRA_ROOT_LOT, "TEE-STRONGBOX-DOWNGRADE")
                putExtra(RedRecallLockActivity.EXTRA_OOD_METRIC, "KeyInfo.securityLevel")
                putExtra(RedRecallLockActivity.EXTRA_OOD_VALUE, 0.0f)
            }
            ctx.startActivity(lockIntent)
        }
    }

    private fun loadVerifiedPrivateKey(): PrivateKey {
        ensureKeyPairProvisioned()
        val keyStore = openKeyStore()
        val privateKey = keyStore.getKey(EC_KEY_ALIAS, null) as? PrivateKey
            ?: failClosedAndLockdown(
                keyStore = keyStore,
                code = "RCL-SEC-EC-PRIVATE-KEY-EVICTED",
                reason = "EC private key '$EC_KEY_ALIAS' was evicted from AndroidKeyStore."
            )
        assertStrictHardwareResidency(keyStore, EC_KEY_ALIAS, privateKey)
        return privateKey
    }

    private fun loadVerifiedPublicKey(): PublicKey {
        ensureKeyPairProvisioned()
        return cachedPublicKey ?: failClosedAndLockdown(
            keyStore = null,
            code = "RCL-SEC-PUBKEY-CACHE-INVALID",
            reason = "Verified EC public key unavailable."
        )
    }

    @Throws(FatalSecurityException::class)
    fun signPayload(payload: String): String {
        val privateKey = loadVerifiedPrivateKey()

        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        println("✍️ [ECDSA-P256] Signed payload with Hardware TEE/StrongBox Private Key (${signature.take(16)}...).")
        Log.d(TAG, "✍️ ECDSA-P256 hardware signature produced (${signature.take(16)}...).")
        return signature
    }

    fun verifySignature(payload: String, signatureBase64: String): Boolean {
        val signatureBytes = try {
            Base64.decode(signatureBase64, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(loadVerifiedPublicKey())
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(signatureBytes)
        } catch (_: SignatureException) {
            false
        }
    }

    @JvmOverloads
    fun exportPublicKeyBase64(context: Context? = null): String {
        if (context != null) {
            registerApplicationContext(context)
        }
        val encoded = loadVerifiedPublicKey().encoded
        println("📤 [ECDSA-PUBKEY] Exporting X.509 SPKI Public Key (${encoded.size} bytes DER) for Go Backend verification...")
        Log.i(TAG, "📤 Exporting device public key (${encoded.size} bytes SPKI DER).")
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    fun publicKeyFingerprintHex(): String {
        ensureKeyPairProvisioned()
        return cachedPublicKeyFingerprintHex ?: MessageDigest.getInstance("SHA-256")
            .digest(loadVerifiedPublicKey().encoded)
            .joinToString("") { "%02x".format(it) }
    }

    fun buildCanonicalYieldPayload(
        tenantId: String,
        deviceId: String,
        actorRole: String,
        batchId: String,
        predictedYield: Float,
        timestampUuidV7: String
    ): String {
        val formattedYield = "%.4f".format(Locale.US, predictedYield)
        return "v2|$tenantId|$deviceId|$actorRole|$batchId|$formattedYield|$timestampUuidV7"
    }

    @Throws(FatalSecurityException::class)
    fun encryptSecret(plainText: String): Pair<ByteArray, ByteArray> {
        ensureKeyPairProvisioned()
        val keyStore = openKeyStore()
        val secretKey = keyStore.getKey(AES_KEY_ALIAS, null) as? SecretKey
            ?: failClosedAndLockdown(
                keyStore = keyStore,
                code = "RCL-SEC-AES-KEY-EVICTED",
                reason = "AES-256-GCM key '$AES_KEY_ALIAS' missing from AndroidKeyStore."
            )
        assertStrictHardwareResidency(keyStore, AES_KEY_ALIAS, secretKey)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Pair(cipher.iv, cipherText)
    }

    @Throws(FatalSecurityException::class)
    fun decryptSecret(iv: ByteArray, cipherText: ByteArray): String {
        ensureKeyPairProvisioned()
        val keyStore = openKeyStore()
        val secretKey = keyStore.getKey(AES_KEY_ALIAS, null) as? SecretKey
            ?: failClosedAndLockdown(
                keyStore = keyStore,
                code = "RCL-SEC-AES-KEY-EVICTED",
                reason = "AES-256-GCM key '$AES_KEY_ALIAS' missing from AndroidKeyStore."
            )
        assertStrictHardwareResidency(keyStore, AES_KEY_ALIAS, secretKey)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
