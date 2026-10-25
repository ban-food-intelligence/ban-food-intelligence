package com.ban.foodintelligence.pos.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
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
import javax.crypto.spec.GCMParameterSpec

/**
 * ============================================================================
 * BAN Food Intelligence - AVATAR ERP Hardware Keystore Manager (Asymmetric)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * WHY ASYMMETRIC: the previous symmetric HMAC key was non-exportable, so the
 * Go backend could never hold the same secret and could never verify a
 * signature. An ECDSA P-256 key pair fixes that: the PRIVATE key never leaves
 * the TEE/StrongBox, and the PUBLIC key ([exportPublicKeyBase64]) is registered
 * with the backend, which verifies signatures with `ecdsa.VerifyASN1`.
 *
 * Wire formats (for the Go side):
 * - Signature : Base64 (no wrap) of an ASN.1 DER `ECDSA-Sig-Value`
 *               (SHA256withECDSA). Verify with `ecdsa.VerifyASN1(pub, sha256(msg), sig)`.
 * - Public key: Base64 (no wrap) of X.509 `SubjectPublicKeyInfo` DER.
 *               Parse with `x509.ParsePKIXPublicKey`.
 *
 * What this does NOT solve: a signature proves "this device's key signed these
 * bytes", not that the sensor data was true. A rooted device can still call the
 * signing oracle. Key attestation and server-side plausibility checks are
 * separate controls.
 */
object KeystoreManager {

    private const val TAG = "KeystoreManager"
    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"

    private const val EC_KEY_ALIAS = "ban_avatar_pos_ecdsa_p256_v1"
    private const val EC_CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Symmetric HMAC alias from the previous design; zeroized on first run. */
    private const val LEGACY_HMAC_KEY_ALIAS = "ban_avatar_pos_hmac_sha256_v1"

    private const val AES_KEY_ALIAS = "ban_avatar_pos_aes_gcm_256_v1"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /**
     * When true (default), [signPayload] refuses to sign if the key is known to
     * be software-only. Set false ONLY for emulators / debug builds. If the
     * hardware level cannot be determined, signing proceeds with a warning.
     */
    @Volatile
    var enforceHardwareBacking: Boolean = true

    @Volatile
    private var hardwareResidencyChecked = false

    // ------------------------------------------------------------------
    // Key provisioning
    // ------------------------------------------------------------------

    private fun openKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    /**
     * Idempotently provisions the P-256 key pair inside the Android Keystore.
     * Lazy (not in an `init` block) so a Keystore failure surfaces as a normal
     * exception at the call site instead of poisoning the singleton.
     */
    @Synchronized
    fun ensureKeyPairProvisioned() {
        val keyStore = openKeyStore()

        if (keyStore.containsAlias(LEGACY_HMAC_KEY_ALIAS)) {
            keyStore.deleteEntry(LEGACY_HMAC_KEY_ALIAS)
            Log.w(TAG, "🧹 Zeroized legacy symmetric HMAC key ($LEGACY_HMAC_KEY_ALIAS).")
        }

        if (!keyStore.containsAlias(EC_KEY_ALIAS)) {
            println("🔐 Provisioning ECDSA P-256 Keypair...")
            Log.i(TAG, "🔐 Provisioning ECDSA P-256 Keypair inside Android Keystore...")
            generateEcKeyPair()
        }
        ensureAesKeyExists(keyStore)
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

    private fun generateEcKeyPair() {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE_PROVIDER
        )
        try {
            // StrongBoxUnavailableException extends ProviderException on API 28+.
            generator.initialize(buildEcSpec(strongBox = true))
            generator.generateKeyPair()
            Log.i(TAG, "🛡️ EC P-256 keypair generated (StrongBox requested).")
        } catch (e: ProviderException) {
            Log.w(TAG, "⚠️ StrongBox unavailable (${e.javaClass.simpleName}); falling back to TEE Keymaster.")
            generator.initialize(buildEcSpec(strongBox = false))
            generator.generateKeyPair()
            Log.i(TAG, "🛡️ EC P-256 keypair generated (TEE / default Keystore).")
        }
    }

    private fun ensureAesKeyExists(keyStore: KeyStore) {
        if (keyStore.containsAlias(AES_KEY_ALIAS)) return
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                AES_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGenerator.generateKey()
    }

    private fun loadPrivateKey(): PrivateKey {
        ensureKeyPairProvisioned()
        return openKeyStore().getKey(EC_KEY_ALIAS, null) as? PrivateKey
            ?: throw SecurityException("EC private key '$EC_KEY_ALIAS' missing from AndroidKeyStore")
    }

    private fun loadPublicKey(): PublicKey {
        ensureKeyPairProvisioned()
        return openKeyStore().getCertificate(EC_KEY_ALIAS)?.publicKey
            ?: throw SecurityException("EC certificate '$EC_KEY_ALIAS' missing from AndroidKeyStore")
    }

    // ------------------------------------------------------------------
    // Hardware residency
    // ------------------------------------------------------------------

    /**
     * @return true if the private key is in TEE/StrongBox, false if software-only,
     *         null if the platform could not tell us.
     */
    fun isHardwareBacked(): Boolean? = runCatching {
        val privateKey = loadPrivateKey()
        val info = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE_PROVIDER)
            .getKeySpec(privateKey, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }.getOrNull()

    private fun assertHardwareResidency() {
        if (hardwareResidencyChecked) return
        when (isHardwareBacked()) {
            true -> {
                Log.i(TAG, "🛡️ Key residency audit: PRIVATE KEY IS HARDWARE-BACKED.")
                hardwareResidencyChecked = true
            }
            false -> {
                if (enforceHardwareBacking) {
                    Log.e(TAG, "💀 Key is SOFTWARE-ONLY and enforceHardwareBacking=true. Refusing to sign.")
                    throw SecurityException("EC signing key is not hardware-backed")
                }
                Log.w(TAG, "⚠️ Key is software-only; allowed because enforceHardwareBacking=false.")
                hardwareResidencyChecked = true
            }
            null -> Log.w(TAG, "⚠️ Could not determine key residency; proceeding without hardware assurance.")
        }
    }

    // ------------------------------------------------------------------
    // Signing / verification / export
    // ------------------------------------------------------------------

    /**
     * Signs [payload] (UTF-8) with the Keystore-resident P-256 private key using
     * SHA256withECDSA.
     *
     * @return Base64 (NO_WRAP) of the ASN.1 DER signature.
     * @throws SecurityException if the key is missing or fails the hardware policy.
     */
    fun signPayload(payload: String): String {
        val privateKey = loadPrivateKey()
        assertHardwareResidency()

        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        println("✍️ [ECDSA-P256] Signed payload with TEE Private Key (${signature.take(16)}...).")
        Log.d(TAG, "✍️ ECDSA-P256 signature produced (${signature.take(16)}...).")
        return signature
    }

    /**
     * Verifies [signatureBase64] over [payload] with this device's public key.
     * Used for local at-rest integrity checks; the authoritative verifier is the
     * backend, using the registered public key.
     */
    fun verifySignature(payload: String, signatureBase64: String): Boolean {
        val signatureBytes = try {
            Base64.decode(signatureBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(loadPublicKey())
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(signatureBytes)
        } catch (e: SignatureException) {
            false
        }
    }

    /**
     * Exports the device public key as Base64 (NO_WRAP) X.509 SubjectPublicKeyInfo
     * DER, for registration with the Go backend. Registration must happen over an
     * authenticated channel; a public key the server has not enrolled out-of-band
     * proves nothing.
     */
    fun exportPublicKeyBase64(): String {
        val encoded = loadPublicKey().encoded
        println("📤 [ECDSA-PUBKEY] Exporting X.509 SPKI Public Key (${encoded.size} bytes DER) for Go Backend verification...")
        Log.i(TAG, "📤 Exporting device public key (${encoded.size} bytes SPKI DER).")
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    /**
     * Lowercase hex SHA-256 of the SPKI DER. Sent with each sync so the server
     * can look up which enrolled key to verify against.
     */
    fun publicKeyFingerprintHex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(loadPublicKey().encoded)
            .joinToString("") { "%02x".format(it) }

    /**
     * Canonical string binding identity claims to the yield telemetry before signing.
     */
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

    // ------------------------------------------------------------------
    // AES-256-GCM secret storage (unchanged)
    // ------------------------------------------------------------------

    fun encryptSecret(plainText: String): Pair<ByteArray, ByteArray> {
        ensureKeyPairProvisioned()
        val secretKey = openKeyStore().getKey(AES_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Pair(cipher.iv, cipherText)
    }

    fun decryptSecret(iv: ByteArray, cipherText: ByteArray): String {
        ensureKeyPairProvisioned()
        val secretKey = openKeyStore().getKey(AES_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
