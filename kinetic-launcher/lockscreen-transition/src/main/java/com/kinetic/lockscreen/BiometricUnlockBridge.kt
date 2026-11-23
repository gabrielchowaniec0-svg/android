package com.kinetic.lockscreen

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the system [BiometricPrompt] (fingerprint / face, or PIN as fallback).
 * On success it optionally plays the cinematic unlock on [controller], then
 * reports the credential tag — used e.g. to reveal hidden apps.
 *
 * This uses the platform BiometricPrompt only — the launcher never sees or
 * stores the biometric itself, just the success signal and an opaque tag the
 * user assigned to a credential in settings.
 */
class BiometricUnlockBridge(
    private val activity: FragmentActivity,
    private val scope: CoroutineScope,
    private val controller: UnlockTransitionController? = null,
) {

    fun interface OnUnlocked {
        /** @param credentialTag the user-assigned tag for the credential used. */
        fun onUnlocked(credentialTag: String)
    }

    /** True when a strong biometric or a screen-lock credential is enrolled. */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

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
                        controller?.runUnlock()
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
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        prompt.authenticate(info)
    }

    companion object {
        const val DEFAULT_TAG = "primary"

        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
