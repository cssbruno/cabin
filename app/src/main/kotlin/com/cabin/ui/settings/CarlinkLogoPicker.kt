package com.cabin.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R

@Composable
internal fun CarlinkLogoPicker(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.carlink_logo)) },
        text = {
            LazyVerticalGrid(columns = GridCells.Adaptive(88.dp), modifier = Modifier.heightIn(max = 320.dp)) {
                items(88) { index ->
                    val bitmap = remember(index) {
                        context.assets.open("carlink/logos/%03d.png".format(java.util.Locale.ROOT, index)).use {
                            BitmapFactory.decodeStream(it).asImageBitmap()
                        }
                    }
                    FilterChip(selected = selected == index, onClick = { onSelect(index) },
                        label = { Image(bitmap, stringResource(R.string.carlink_logo_number, index + 1), Modifier.size(64.dp)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(-1) }) { Text(stringResource(R.string.carlink_logo_default)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
