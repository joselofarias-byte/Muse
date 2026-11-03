package io.zer0.muse.terminal.bridge

import android.content.Context

/** Explicit user grants for sensitive Nightzuku actions. There is no tool that can flip these. */
internal class NightzukuConsentStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun consent(): NightzukuGate.Consent = NightzukuGate.Consent(
        modify = prefs.getBoolean(MODIFY, false),
        destructive = prefs.getBoolean(DESTRUCTIVE, false),
    )

    fun setModify(enabled: Boolean) {
        prefs.edit().putBoolean(MODIFY, enabled).apply()
    }

    fun setDestructive(enabled: Boolean) {
        prefs.edit().putBoolean(DESTRUCTIVE, enabled).apply()
    }

    private companion object {
        const val PREFS = "nightzuku_bridge"
        const val MODIFY = "modify"
        const val DESTRUCTIVE = "destructive"
    }
}
