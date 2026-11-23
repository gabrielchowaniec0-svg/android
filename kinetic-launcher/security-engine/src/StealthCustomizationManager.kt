package com.kinetic.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Local personalization + privacy manager for the launcher, controlled entirely
 * by the device owner.
 *
 * Two things it does:
 *
 *  1. Icon & label overrides — within THIS launcher's own rendering, an app can
 *     be shown with a custom label and a custom icon (from an installed icon
 *     pack / theme). Nothing about the underlying app or the system changes;
 *     only how the launcher draws the entry changes.
 *
 *  2. A private workspace — the owner can move chosen apps into a "private"
 *     space that is hidden from the default home screen and revealed only when
 *     they authenticate with a credential they've tagged for it (see
 *     BiometricUnlockBridge). This is the usual launcher "hidden apps" pattern,
 *     e.g. keeping a banking or photo-vault app off the main grid.
 *
 * Settings are stored in EncryptedSharedPreferences so the owner's own
 * customization data is protected at rest. There is no network component and
 * no access to other apps' data.
 */
class StealthCustomizationManager(context: Context) {

    enum class Workspace { PRIMARY, PRIVATE }

    data class IconMask(
        val packageName: String,
        val overrideLabel: String? = null,
        /** Resource/key the launcher's icon renderer maps to a themed drawable. */
        val overrideIconKey: String? = null,
    )

    /** What the launcher should actually draw for an app entry. */
    data class DisplayEntry(
        val packageName: String,
        val label: String,
        val iconKey: String?,
    )

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ---- observable state for Compose ------------------------------------
    private val _masks = MutableStateFlow(loadMasks())
    val masks: StateFlow<Map<String, IconMask>> = _masks.asStateFlow()

    private val _privateApps = MutableStateFlow(loadStringSet(KEY_PRIVATE))
    val privateApps: StateFlow<Set<String>> = _privateApps.asStateFlow()

    private val _activeWorkspace = MutableStateFlow(Workspace.PRIMARY)
    val activeWorkspace: StateFlow<Workspace> = _activeWorkspace.asStateFlow()

    // ---- icon / label masking --------------------------------------------
    fun setMask(mask: IconMask) {
        val next = _masks.value.toMutableMap().apply { put(mask.packageName, mask) }
        _masks.value = next
        persistMasks(next)
    }

    fun clearMask(packageName: String) {
        val next = _masks.value.toMutableMap().apply { remove(packageName) }
        _masks.value = next
        persistMasks(next)
    }

    fun maskFor(packageName: String): IconMask? = _masks.value[packageName]

    /** Resolve what the launcher should draw, applying any override. */
    fun resolveDisplay(packageName: String, systemLabel: String, systemIconKey: String?): DisplayEntry {
        val mask = _masks.value[packageName]
        return DisplayEntry(
            packageName = packageName,
            label = mask?.overrideLabel ?: systemLabel,
            iconKey = mask?.overrideIconKey ?: systemIconKey,
        )
    }

    // ---- private workspace -----------------------------------------------
    fun moveToPrivate(packageName: String) = updatePrivate { it + packageName }
    fun moveToPrimary(packageName: String) = updatePrivate { it - packageName }
    fun isPrivate(packageName: String): Boolean = packageName in _privateApps.value

    /**
     * Map an authenticated credential to the workspace it should open. The owner
     * assigns tags to their credentials in settings; an unmapped tag falls back
     * to PRIMARY, so a normal unlock never exposes the private space.
     */
    fun workspaceForCredential(credentialTag: String): Workspace {
        val privateTag = prefs.getString(KEY_PRIVATE_TAG, null)
        val ws = if (privateTag != null && credentialTag == privateTag) {
            Workspace.PRIVATE
        } else {
            Workspace.PRIMARY
        }
        _activeWorkspace.value = ws
        return ws
    }

    /** Owner opts a credential tag in as the key to the private space. */
    fun bindPrivateCredential(credentialTag: String) {
        prefs.edit().putString(KEY_PRIVATE_TAG, credentialTag).apply()
    }

    fun unbindPrivateCredential() {
        prefs.edit().remove(KEY_PRIVATE_TAG).apply()
    }

    /** Collapse back to the primary space (e.g. on screen-off / re-lock). */
    fun lockToPrimary() { _activeWorkspace.value = Workspace.PRIMARY }

    /**
     * Filter an app list for the given workspace: PRIMARY hides private apps,
     * PRIVATE shows everything.
     */
    fun visiblePackages(all: List<String>, workspace: Workspace): List<String> =
        when (workspace) {
            Workspace.PRIMARY -> all.filterNot { it in _privateApps.value }
            Workspace.PRIVATE -> all
        }

    // ---- persistence helpers ---------------------------------------------
    private inline fun updatePrivate(transform: (Set<String>) -> Set<String>) {
        val next = transform(_privateApps.value)
        _privateApps.value = next
        prefs.edit().putStringSet(KEY_PRIVATE, next).apply()
    }

    private fun loadStringSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun loadMasks(): Map<String, IconMask> {
        val raw = prefs.getString(KEY_MASKS, null) ?: return emptyMap()
        val obj = JSONObject(raw)
        val out = HashMap<String, IconMask>()
        for (pkg in obj.keys()) {
            val m = obj.getJSONObject(pkg)
            out[pkg] = IconMask(
                packageName = pkg,
                overrideLabel = m.optString("label").ifEmpty { null },
                overrideIconKey = m.optString("icon").ifEmpty { null },
            )
        }
        return out
    }

    private fun persistMasks(masks: Map<String, IconMask>) {
        val obj = JSONObject()
        masks.forEach { (pkg, m) ->
            obj.put(pkg, JSONObject().apply {
                put("label", m.overrideLabel ?: "")
                put("icon", m.overrideIconKey ?: "")
            })
        }
        prefs.edit().putString(KEY_MASKS, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kinetic_customization"
        private const val KEY_MASKS = "icon_masks"
        private const val KEY_PRIVATE = "private_apps"
        private const val KEY_PRIVATE_TAG = "private_credential_tag"
    }
}
