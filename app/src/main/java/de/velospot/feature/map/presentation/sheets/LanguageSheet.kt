package de.velospot.feature.map.presentation.sheets

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import de.velospot.R
import de.velospot.core.locale.LanguagePreferences
import de.velospot.feature.map.presentation.headingSemantics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSheet(
    currentLanguageCode: String,
    onDismiss: () -> Unit,
    onSelectLanguage: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.language_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.headingSemantics()
            )
            Spacer(modifier = Modifier.height(12.dp))

            SUPPORTED_LANGUAGES.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { language ->
                        val isSelected = language.code == currentLanguageCode
                        if (isSelected) {
                            Button(
                                onClick = { onSelectLanguage(language.code) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = language.flag)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelectLanguage(language.code) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = language.flag)
                            }
                        }
                    }
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

internal fun applyLanguageSelection(context: Context, languageCode: String) {
    LanguagePreferences.saveLanguageCode(context, languageCode)
    AppCompatDelegate.setApplicationLocales(
        LocaleListCompat.forLanguageTags(languageCode)
    )
    (context as? Activity)?.recreate()
}

internal fun resolveCurrentLanguageCode(context: Context, fallbackLanguage: String): String {
    val appLocaleLanguage = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    if (appLocaleLanguage.isNotBlank()) return appLocaleLanguage

    return LanguagePreferences.getSavedLanguageCode(context)
        ?: fallbackLanguage.ifBlank { "en" }
}

internal fun languageFlagForCode(languageCode: String): String {
    return SUPPORTED_LANGUAGES.firstOrNull { it.code == languageCode }?.flag ?: "🏳️"
}

private data class SupportedLanguage(
    val code: String,
    val flag: String
)

private val SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage(code = "de", flag = "🇩🇪"),
    SupportedLanguage(code = "en", flag = "🇬🇧"),
    SupportedLanguage(code = "fr", flag = "🇫🇷"),
    SupportedLanguage(code = "it", flag = "🇮🇹"),
    SupportedLanguage(code = "pt", flag = "🇵🇹"),
    SupportedLanguage(code = "lb", flag = "🇱🇺"),
    SupportedLanguage(code = "nl", flag = "🇳🇱"),
    SupportedLanguage(code = "es", flag = "🇪🇸")
)

