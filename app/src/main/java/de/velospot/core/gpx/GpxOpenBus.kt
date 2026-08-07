package de.velospot.core.gpx

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level hand-off for a `.gpx` file opened from **outside** the app via an
 * `ACTION_VIEW` intent (file manager, e-mail attachment, browser download, share
 * sheet, …).
 *
 * [de.velospot.MainActivity] receives the intent — possibly on a cold start,
 * before the map's `MapViewModel` even exists — and [post]s the incoming [Uri]
 * here. The ViewModel collects [pending] once it is created and shows the
 * import-or-preview chooser, then [consume]s the value so a configuration change
 * (rotation) does not re-trigger the dialog. A [StateFlow] (not a one-shot event)
 * is used deliberately so a value posted before any collector exists is retained
 * and delivered as soon as the ViewModel starts observing.
 */
@Singleton
class GpxOpenBus @Inject constructor() {

    private val _pending = MutableStateFlow<Uri?>(null)

    /** The GPX [Uri] awaiting the chooser, or `null` when there is none. */
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    /** Publishes an opened GPX [uri] for the map layer to pick up. */
    fun post(uri: Uri) { _pending.value = uri }

    /** Clears the pending uri once the user has acted on the chooser. */
    fun consume() { _pending.value = null }
}

