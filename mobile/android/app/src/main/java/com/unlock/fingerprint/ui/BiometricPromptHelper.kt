package com.unlock.fingerprint.ui

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.unlock.fingerprint.crypto.KeyStoreManager
import java.security.Signature

class BiometricPromptHelper(
    private val activity: FragmentActivity,
    private val onAuthSuccess: (signature: ByteArray) -> Unit,
    private val onAuthError: (errorMsg: String) -> Unit
) {

    fun showBiometricPrompt(challengeToSign: ByteArray) {
        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Windows Laptop")
            .setSubtitle("Confirm your fingerprint to authenticate")
            .setNegativeButtonText("Cancel")
            .setConfirmationRequired(true)
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val signature: Signature = result.cryptoObject?.signature 
                            ?: throw IllegalStateException("CryptoObject signature was null")

                        signature.update(challengeToSign)
                        val signatureBytes = signature.sign()

                        onAuthSuccess(signatureBytes)
                    } catch (e: Exception) {
                        onAuthError("Signature error: ${e.message}")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onAuthError("Biometric error ($errorCode): $errString")
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onAuthError("Fingerprint not recognized")
                }
            }
        )

        // Initialize hardware signature
        val uninitializedSignature = KeyStoreManager.initSignatureForSigning()
        biometricPrompt.authenticate(
            promptInfo,
            BiometricPrompt.CryptoObject(uninitializedSignature)
        )
    }
}
