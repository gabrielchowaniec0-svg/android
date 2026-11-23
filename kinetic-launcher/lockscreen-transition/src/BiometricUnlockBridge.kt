package com.kinetic.lockscreen

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wires a successful [BiometricPrompt] authentication to the cinematic unlock,
 * and reports which credential the user authenticated with so the launcher can
 * decide which workspace to open (see StealthCustomizationManager).
 *
 * This uses the platform BiometricPrompt only — the launcher never sees or
 * stores the biometric itself, just the success signal and an opaque tag the
 * user assigned to a credential in settings.
 */
class BiometricUnlockBridge(
    private val activity: FragmentActivity,
    private val scope: CoroutineScope,
    private val controller: UnlockTransitionController,
) {

    fun interface OnUnlocked {
        /** @param credentialTag the user-assigned tag for the credential used. */
        fun onUnlocked(credentialTag: String)
    }

    fun authenticate(
        title: String = "Unlock",
        subtitle: String = "Confirm it's you",
        credentialTag: String = DEFAULT_TAG,
        onUnlocked: OnUnlocked,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    scope.launch {
                        controller.runUnlock()
                        onUnlocked.onUnlocked(credentialTag)
                    }
                }
                // onAuthenticationError / onAuthenticationFailed left to the host UI,
                // which should keep the lock screen blurred and offer a retry.
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        prompt.authenticate(info)
    }

    companion object {
        const val DEFAULT_TAG = "primary"
    }
}
