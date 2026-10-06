package com.gh00ul.cascade.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.searchApps
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.util.LauncherActions

/** Full-screen search. Hidden apps still show up here. Enter opens the top hit, or searches the web. */
@Composable
fun SearchOverlay(
    apps: List<AppEntry>,
    icons: Map<String, IconImage>,
    showIcons: Boolean,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, apps) { searchApps(apps, query) }
    val style = LocalLauncherStyle.current

    fun submit() {
        val top = results.firstOrNull()
        if (top != null) {
            onLaunch(top, null)
        } else if (query.isNotBlank()) {
            LauncherActions.webSearch(context, query.trim())
            onDismiss()
        }
    }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Box(
        Modifier
            .fillMaxSize()
            // A pane for TalkBack; it also keeps the home screen underneath out of reach.
            .semantics { paneTitle = "Search" }
            .background(style.scrim.copy(alpha = 0.94f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = style.content.copy(alpha = 0.12f),
                contentColor = style.content,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 52.dp)
                    // Taps on the icon or padding go to the field, not through to the dismiss handler behind.
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                    },
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = style.content, fontSize = 17.sp),
                        cursorBrush = SolidColor(style.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus),
                        // The placeholder sits inside the field so TalkBack reads it as the field's name.
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        "Search apps or the web",
                                        color = style.content.copy(alpha = 0.55f),
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                items(results, key = { it.key }) { app ->
                    AppRow(
                        app = app,
                        icon = icons[app.key],
                        notifications = emptyList(),
                        showIcon = showIcons,
                        showPreview = false,
                        large = false,
                        onClick = { onLaunch(app, it) },
                        onLongClick = { onLongPress(app) },
                        onNotificationClick = {},
                    )
                }
                if (query.isNotBlank()) {
                    item(key = "web") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LauncherActions.webSearch(context, query.trim())
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = style.content.copy(alpha = 0.7f))
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "Search the web for “${query.trim()}”",
                                style = style.app.copy(fontSize = 17.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
