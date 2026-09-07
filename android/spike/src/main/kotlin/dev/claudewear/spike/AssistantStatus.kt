package dev.claudewear.spike

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * What the system currently believes about the assistant. Read every time the screen is shown,
 * so the user can flip a setting, come back, and see whether it took.
 */
data class AssistantStatus(
    val roleAvailable: Boolean,
    val roleHeld: Boolean,
    val assistantSetting: String?,
    val voiceInteractionSetting: String?,
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdk: Int,
    val display: String,
) {
    companion object {
        fun read(context: Context): AssistantStatus {
            val rm = context.getSystemService(RoleManager::class.java)
            val resolver = context.contentResolver
            return AssistantStatus(
                roleAvailable = rm?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ?: false,
                roleHeld = rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false,
                // Both are world-readable secure settings on stock Android. If a Samsung build
                // hides them we learn that too: the screen shows "null".
                assistantSetting = Settings.Secure.getString(resolver, "assistant"),
                voiceInteractionSetting = Settings.Secure.getString(resolver, "voice_interaction_service"),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                release = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                display = Build.DISPLAY,
            )
        }
    }
}
