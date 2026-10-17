package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import jp.kjtr.drivelogger.R

@Composable
fun DriveTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = colorResource(R.color.brand_primary_dark),
            primaryContainer = colorResource(R.color.brand_container_dark),
            onPrimaryContainer = colorResource(R.color.brand_on_container_dark),
            secondary = colorResource(R.color.brand_accent),
            secondaryContainer = colorResource(R.color.brand_container_dark),
            onSecondaryContainer = colorResource(R.color.brand_on_container_dark),
            tertiary = colorResource(R.color.brand_accent),
        )
    } else {
        lightColorScheme(
            primary = colorResource(R.color.brand_primary),
            primaryContainer = colorResource(R.color.brand_container),
            onPrimaryContainer = colorResource(R.color.brand_on_container),
            secondary = colorResource(R.color.brand_accent),
            secondaryContainer = colorResource(R.color.brand_container),
            onSecondaryContainer = colorResource(R.color.brand_on_container),
            tertiary = colorResource(R.color.brand_accent),
            surface = colorResource(R.color.brand_surface),
            background = colorResource(R.color.brand_surface),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
