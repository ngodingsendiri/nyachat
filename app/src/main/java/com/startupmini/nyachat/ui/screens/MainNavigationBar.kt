package com.startupmini.nyachat.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.startupmini.nyachat.R

/**
 * NavigationBar tab Chat/Rekap. Ekstraksi dari MainActivity (TASK-1.3) —
 * tanpa perubahan behavior. Keyboard disembunyikan saat pindah tab (BUG-02).
 */
@Composable
fun MainNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = {
                keyboardController?.hide()
                onTabSelected(0)
            },
            icon = {
                Icon(
                    imageVector = if (selectedTab == 0) Icons.Rounded.ChatBubble else Icons.Rounded.ChatBubbleOutline,
                    contentDescription = stringResource(R.string.tab_chat_desc)
                )
            },
            label = { Text(stringResource(R.string.tab_diskusi)) },
            modifier = Modifier.testTag("tab_chat")
        )

        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = {
                keyboardController?.hide()
                onTabSelected(1)
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.PieChart,
                    contentDescription = stringResource(R.string.tab_rekap_desc)
                )
            },
            label = { Text(stringResource(R.string.tab_rekap)) },
            modifier = Modifier.testTag("tab_rekap")
        )
    }
}
