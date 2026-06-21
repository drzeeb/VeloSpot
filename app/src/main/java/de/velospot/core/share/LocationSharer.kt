package de.velospot.core.share

import android.content.Context
import android.content.Intent

/**
 * Shares a geographic location with other apps via the system share sheet.
 *
 * The location is shared as a universal OpenStreetMap web link, which opens in any
 * browser as well as OSM-aware apps — no temp files, just an `ACTION_SEND` of
 * `text/plain` (symmetric counterpart of [ImageSharer]).
 */
object LocationSharer {

    /**
     * Opens the system share sheet for the given coordinate.
     *
     * @param label optional human-readable name/address, prepended to the link and
     *  used as the share subject; pass null to share the link only.
     * @param chooserTitle title shown on the system share sheet.
     */
    fun shareLocation(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String?,
        chooserTitle: String,
    ) {
        val webUrl = "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=17/$latitude/$longitude"

        val text = buildString {
            label?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            append(webUrl)
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            label?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }

        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

