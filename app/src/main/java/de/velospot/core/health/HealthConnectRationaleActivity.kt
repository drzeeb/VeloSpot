package de.velospot.core.health

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Tiny activity that satisfies Health Connect's **permissions-rationale** contract.
 *
 * Health Connect launches this (via the
 * `androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE` intent, and on
 * API 34+ the `android.intent.action.VIEW_PERMISSION_USAGE` privacy-usage intent)
 * when the user wants to understand why VeloSpot requests health permissions. It
 * simply opens VeloSpot's existing public privacy policy in the browser and closes.
 */
class HealthConnectRationaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    private companion object {
        /** VeloSpot's public privacy policy (reused from the About sheet). */
        const val PRIVACY_URL = "https://velospot.app/privacy"
    }
}

