package com.cabin.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.localization.AppLanguage

@Composable
internal fun LanguageSettingsSection() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.selected(context)) }
    SettingsSection(stringResource(R.string.app_language_title), stringResource(R.string.app_language_detail)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (listOf("" to stringResource(R.string.app_language_system)) + AppLanguage.names.toList()).forEach { (tag, title) ->
                FilterChip(selected = tag == language, onClick = {
                    AppLanguage.select(context, tag)
                    language = tag
                    languageActivity(context)?.recreate()
                }, label = { Text(title) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
    }
}

private fun languageActivity(context: Context): Activity? {
    var current = context
    repeat(12) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}
