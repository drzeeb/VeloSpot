package de.velospot.core.backup

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi

/**
 * Pure (de)serialiser for the two JSON documents that make up a backup: the
 * [BackupManifest] and the [BackupData] payload. Takes a [Moshi] so it stays free
 * of any framework wiring and is trivially JVM-unit-testable.
 *
 * Every `decode*` returns `null` on malformed / foreign input instead of throwing,
 * so a corrupt or unrelated file surfaces as a friendly error rather than a crash.
 */
class BackupSerializer(moshi: Moshi) {

    private val manifestAdapter = moshi.adapter(BackupManifest::class.java)
    private val dataAdapter = moshi.adapter(BackupData::class.java)

    fun encodeManifest(manifest: BackupManifest): String =
        manifestAdapter.indent("  ").toJson(manifest)

    fun encodeData(data: BackupData): String =
        dataAdapter.toJson(data)

    /** Parses a manifest, or returns `null` when the JSON is missing/corrupt. */
    fun decodeManifest(json: String?): BackupManifest? {
        if (json.isNullOrBlank()) return null
        return runCatching { manifestAdapter.fromJson(json) }
            .getOrElse { if (it is JsonDataException || it is Exception) null else throw it }
    }

    /** Parses the data payload, or returns `null` when the JSON is missing/corrupt. */
    fun decodeData(json: String?): BackupData? {
        if (json.isNullOrBlank()) return null
        return runCatching { dataAdapter.fromJson(json) }
            .getOrElse { if (it is JsonDataException || it is Exception) null else throw it }
    }
}

