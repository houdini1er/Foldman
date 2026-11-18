package dev.foldman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.RadioStation
import dev.foldman.deck.RadioUi

class RadioActions(
    val play: (List<RadioStation>, Int) -> Unit,
    val search: (String) -> Unit,
    val closeSearch: () -> Unit,
    val toggleFavourite: (RadioStation) -> Unit,
)

/**
 * The web radio shelf: a search box (station name, or a stream address to play directly), then
 * the saved stations or the search results. Tap a station to tune in, the star to save or forget it.
 * [light] draws it for the paper J-card on the cover screen, otherwise for the dark glass panel.
 */
@Composable
fun RadioBrowser(radio: RadioUi, actions: RadioActions, modifier: Modifier = Modifier, light: Boolean = false) {
    val ink = if (light) DeckColors.Ink else Color(0xFFF4EFE9)
    val sub = if (light) DeckColors.Ink.copy(alpha = 0.6f) else Color(0xFFA79C92)
    val title = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(color = sub, fontSize = 12.sp, lineHeight = 16.sp)
    val link = small.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold)
    val focus = LocalFocusManager.current

    Column(modifier) {
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            text, { text = it },
            singleLine = true,
            placeholder = { Text("Search a station, or paste a stream URL", style = small) },
            textStyle = small.copy(color = ink, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                actions.search(text)
                focus.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))

        val searching = radio.query != null
        val stations = if (searching) radio.results else radio.favourites
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    radio.searching -> "Searching…"
                    searching -> "${stations.size} stations"
                    else -> "Saved stations · ${stations.size}"
                },
                style = small, modifier = Modifier.weight(1f),
            )
            if (searching) {
                Text("‹ Saved", style = link, modifier = Modifier.clickable {
                    text = ""
                    actions.closeSearch()
                }.padding(4.dp))
            }
        }
        radio.message?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = small.copy(color = Color(0xFFE0604A)))
        }
        if (!searching && stations.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("No saved stations. Search for one and tap ☆ to keep it here.", style = small)
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn {
            itemsIndexed(stations, key = { _, s -> s.url }) { i, s ->
                val onAir = s.url == radio.onAir
                val saved = radio.favourites.any { it.url == s.url }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (onAir) DeckColors.Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { actions.play(stations, i) }
                        .padding(vertical = 5.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UrlThumb(s.favicon, Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = title.copy(fontWeight = if (onAir) FontWeight.Bold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.info?.let { Text(it, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (onAir) Text("ON AIR", style = small.copy(color = DeckColors.Accent, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    Text(
                        if (saved) "★" else "☆",
                        style = title.copy(color = if (saved) DeckColors.Accent else sub, fontSize = 20.sp),
                        modifier = Modifier.clickable { actions.toggleFavourite(s) }.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
