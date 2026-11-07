package com.oak404.redcast.ui

import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun CastButton(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
) {
    if (!viewModel.cast.available) return
    // The button takes its colour from the view theme when it's created.
    key(onDark) {
        AndroidView(
            factory = { context ->
                val theme = if (onDark) {
                    androidx.appcompat.R.style.Theme_AppCompat_NoActionBar
                } else {
                    androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar
                }
                MediaRouteButton(ContextThemeWrapper(context, theme)).apply {
                    CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
                    // Stay visible with no devices found, so the picker can say why.
                    setAlwaysVisible(true)
                }
            },
            modifier = modifier.size(48.dp),
        )
    }
}
