package com.chencohensolution.batterytime.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohensolution.batterytime.CompanionClient
import com.chencohensolution.batterytime.Pairing
import java.util.concurrent.ConcurrentHashMap

/** App icons from the Mac, fetched once per app while the app runs */
private object IconCache {
    val icons = ConcurrentHashMap<String, ImageBitmap>()
    val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

@Composable
fun AppIcon(name: String, client: CompanionClient, pairing: Pairing, size: Dp = 32.dp) {
    val icon by produceState(IconCache.icons[name], name, pairing.host) {
        if (value != null || name in IconCache.missing) return@produceState
        val result = runCatching { client.icon(pairing, name) }
        if (result.isFailure) return@produceState   // not reachable now: try again next time
        val bytes = result.getOrNull()
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        if (bmp != null) IconCache.icons[name] = bmp else IconCache.missing += name   // the app has quit
        value = bmp
    }
    val i = icon
    if (i != null) {
        Image(i, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(size * 0.6f))
        }
    }
}
