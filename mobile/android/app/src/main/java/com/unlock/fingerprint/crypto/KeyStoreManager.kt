package com.unlock.fingerprint.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object KeyStoreManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "BiometricUnlockHardwareKey"

    /**
     * Initializes or retrieves the hardware-backed ECDSA P-256 key pair.
     * The private key is strictly locked behind hardware biometric authentication.
     */
    fun getOrCreateHardwareKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )

            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).apply {
                setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                setDigests(KeyProperties.DIGEST_SHA256)
                // Require genuine biometric authentication to unlock private key
                setUserAuthenticationRequired(true)
                // Invalidate key if new fingerprints are enrolled
                setInvalidatedByBiometricEnrollment(true)
            }.build()

            keyPairGenerator.initialize(parameterSpec)
            return keyPairGenerator.generateKeyPair()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    /**
     * Prepares an uninitialized Signature object to be passed into BiometricPrompt.CryptoObject.
     */
    fun initSignatureForSigning(): Signature {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) 
            ?: getOrCreateHardwareKeyPair().private

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        return signature
    }

    /**
     * Returns the DER-encoded public key bytes for pairing with Windows PC.
     */
    fun getPublicKeyDer(): ByteArray {
        return getOrCreateHardwareKeyPair().public.encoded
    }
}
