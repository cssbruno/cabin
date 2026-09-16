package com.cabin.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R

@Composable
internal fun HeadUnitSettingsPanel() {
    val context = LocalContext.current
    val home = com.cabin.launcher.rememberDefaultHomeState()
    SettingsSection(stringResource(R.string.headunit_home), stringResource(if (home.isDefault) R.string.home_active else R.string.home_setup_detail)) {
        FilledTonalButton(onClick = { home.choose() }, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(if (home.isDefault) R.string.home_change else R.string.home_set_default))
        }
        if (home.unavailable) SettingsNotice(stringResource(R.string.headunit_unavailable))
    }
    val system = context.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    val widgetBinding = context.checkSelfPermission("android.permission.BIND_APPWIDGET") == PackageManager.PERMISSION_GRANTED
    SettingsDisclosure(stringResource(R.string.headunit_settings), stringResource(if (system) R.string.headunit_system else R.string.headunit_regular)) {
        Text(stringResource(if (widgetBinding) R.string.headunit_widget_granted else R.string.headunit_widget_consent))
    }
}
