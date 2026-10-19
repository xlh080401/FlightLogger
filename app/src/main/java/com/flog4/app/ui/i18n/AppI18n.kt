package com.flog4.app.ui.i18n

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class AppLanguage(val code: String, val badgeText: String) {
    ZH("zh", "中文"),
    EN("en", "EN");

    val isChinese: Boolean get() = this == ZH
}

/**
 * Global reactive language state manager supporting instant in-app Chinese / English switching
 * as well as persistence across app restarts.
 */
object AppLocaleManager {
    private const val PREFS_NAME = "aerolog_i18n_prefs"
    private const val KEY_LANG = "app_language"

    private val _languageFlow = MutableStateFlow(defaultLanguage())
    val languageFlow: StateFlow<AppLanguage> = _languageFlow

    val currentLanguage: AppLanguage
        get() = _languageFlow.value

    val isChinese: Boolean
        get() = _languageFlow.value == AppLanguage.ZH

    private fun defaultLanguage(): AppLanguage {
        val sysLang = Locale.getDefault().language.lowercase(Locale.US)
        return if (sysLang.startsWith("en")) AppLanguage.EN else AppLanguage.ZH
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, null)
        if (saved != null) {
            _languageFlow.value = if (saved == "en") AppLanguage.EN else AppLanguage.ZH
        }
    }

    fun setLanguage(lang: AppLanguage, context: Context? = null) {
        _languageFlow.value = lang
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LANG, lang.code)
            ?.apply()
    }

    fun toggleLanguage(context: Context? = null): AppLanguage {
        val next = if (_languageFlow.value == AppLanguage.ZH) AppLanguage.EN else AppLanguage.ZH
        setLanguage(next, context)
        return next
    }

    fun tr(zh: String, en: String): String = if (isChinese) zh else en
}

val LocalAppLanguage = compositionLocalOf { AppLanguage.ZH }

/**
 * Returns the localized string in Compose based on [LocalAppLanguage].
 */
@Composable
@ReadOnlyComposable
fun s(zh: String, en: String): String {
    return if (LocalAppLanguage.current == AppLanguage.ZH) zh else en
}

/**
 * Material Design 3 Segmented Pill Button for switching between Chinese (中文) and English (EN).
 */
@Composable
fun Md3LanguageSwitchPill(
    modifier: Modifier = Modifier,
    onLanguageChanged: ((AppLanguage) -> Unit)? = null
) {
    val context = LocalContext.current
    val currentLang by AppLocaleManager.languageFlow.collectAsState()

    Surface(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable {
                val next = AppLocaleManager.toggleLanguage(context)
                onLanguageChanged?.invoke(next)
            },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = "Switch Language / 切换语言",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "中",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (currentLang == AppLanguage.ZH) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (currentLang == AppLanguage.ZH) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                }
            )
            Text(
                text = " / ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = "EN",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (currentLang == AppLanguage.EN) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (currentLang == AppLanguage.EN) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                }
            )
        }
    }
}
