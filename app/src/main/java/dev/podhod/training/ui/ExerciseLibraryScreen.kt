package dev.podhod.training.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.podhod.core.*
import dev.podhod.training.TrainingViewModel
import kotlinx.serialization.encodeToString

@Composable fun ExerciseLibraryScreen(model: TrainingViewModel, planned: List<PlannedExercise>, add: (Exercise) -> Unit, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<String?>(null) }
    var equipment by rememberSaveable { mutableStateOf<String?>(null) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var added by rememberSaveable { mutableIntStateOf(0) }
    val catalog = model.catalog
    val archive by model.archive.collectAsStateWithLifecycle()
    val saved = (archive.week.flatMap { it.exercises }.map { it.exercise } +
        archive.scheduledWeeks.flatMap { it.workouts }.flatMap { it.exercises }.map { it.exercise } +
        archive.active?.session?.exercises.orEmpty().map { it.exercise } + archive.history.flatMap { it.exercises }.map { it.exercise })
        .distinctBy { it.id }.filter { catalog.entry(it.id) == null }
    val results = catalog.search(query, muscle, equipment).map { it.exercise } +
        if (muscle == null && equipment == null) saved.filter { query.isBlank() || it.name.contains(query, true) || it.muscle.contains(query, true) } else emptyList()
    FullScreen("Каталог упражнений", back, actions = { TextButton(onClick = { custom = true }) { Text("Своё") } }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().imePadding(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Название, мышцы, оборудование") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                CatalogFilter("Все мышцы", catalog.muscles, muscle) { muscle = it }
                CatalogFilter("Всё оборудование", catalog.equipment, equipment) { equipment = it }
                Text("Найдено: ${results.size} · В тренировке: ${planned.size}", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (added > 0) Text("Добавлено: $added. Можно выбрать ещё.", color = Lime)
            }
            if (results.isEmpty()) item {
                Text("Ничего не найдено", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { query = ""; muscle = null; equipment = null }) { Text("Показать все упражнения") }
            }
            items(results, key = { it.id.toString() }) { exercise ->
                val canonical = catalog.entry(exercise.id)?.exercise?.id ?: exercise.id
                val count = planned.count { (catalog.entry(it.exercise.id)?.exercise?.id ?: it.exercise.id) == canonical }
                GymCard {
                    Row {
                        TextButton(onClick = { detail = archiveJson.encodeToString(exercise) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(8.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(exercise.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                Text(exercise.muscle, color = Muted, style = MaterialTheme.typography.bodySmall)
                                catalog.entry(exercise.id)?.let { entry ->
                                    Text(catalog.equipment.filter { it.id in entry.equipment }.joinToString { it.name }, color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                if (count > 0) Text("В тренировке: $count", color = Lime, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { add(exercise); added++ }) { Icon(Icons.Default.AddCircle, "Добавить: ${exercise.name}", tint = Lime) }
                    }
                }
            }
        }
    }
    if (custom) AlertDialog(onDismissRequest = { custom = false }, title = { Text("Своё упражнение") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") })
            OutlinedTextField(group, { group = it }, label = { Text("Группа мышц, необязательно") })
            Text("Без разметки мышц подходы не включаются в плановую нагрузку.", color = Muted)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        add(Exercise(name = name.trim(), muscle = group.trim().ifBlank { "Своё упражнение" }, cue = "Записывай рабочий вес и количество повторений в каждом подходе."))
        name = ""; group = ""; added++; custom = false
    }) { Text("Добавить") } }, dismissButton = { TextButton(onClick = { custom = false }) { Text("Отмена") } })
    detail?.let { text -> FullScreenDialog({ detail = null }) { ExerciseDetailScreen(catalog, archiveJson.decodeFromString(text), { detail = null }, add = { add(it); added++; detail = null }) } }
}
@Composable private fun CatalogFilter(title: String, options: List<Taxonomy>, selected: String?, choose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.id == selected }?.name ?: title) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(title) }, onClick = { choose(null); open = false })
            options.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { choose(item.id); open = false }) }
        }
    }
}
@Composable fun ExerciseDetailScreen(catalog: ExerciseCatalog, exercise: Exercise, back: () -> Unit, add: ((Exercise) -> Unit)? = null) {
    val entry = catalog.entry(exercise.id)
    FullScreen("Об упражнении", back) { padding ->
        ScreenColumn(Modifier.padding(padding)) {
            ScreenHeading(exercise.name, exercise.muscle)
            ExerciseArt(exercise.illustration.ifBlank { entry?.exercise?.illustration ?: "curl" })
            if (entry == null) GymCard {
                Text(exercise.cue)
                Text("Для своего или ранее сохранённого упражнения разметка мышц и видео могут быть недоступны.", color = Muted)
            } else {
                GymCard {
                    Text("Основные мышцы", style = MaterialTheme.typography.titleMedium)
                    Text(catalog.muscles.filter { it.id in entry.primaryMuscles }.joinToString { it.name }.ifBlank { "Не указаны в источнике" }, color = Lime)
                    Text("Вспомогательные мышцы", style = MaterialTheme.typography.titleMedium)
                    Text(catalog.muscles.filter { it.id in entry.secondaryMuscles && it.id !in entry.primaryMuscles }.joinToString { it.name }.ifBlank { "Не учитываются в расчёте" }, color = Muted)
                    Text("Оборудование: " + catalog.equipment.filter { it.id in entry.equipment }.joinToString { it.name }, color = Muted)
                    Text("Русские пояснения и разметка мышц — Подход", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text("Как выполнять", style = MaterialTheme.typography.titleLarge)
                entry.instructions.forEachIndexed { index, instruction -> Text("${index + 1}. $instruction") }
                if (entry.instructions.isEmpty()) Text(entry.exercise.cue, color = Muted)
                if (exercise.cue.isNotBlank() && exercise.cue.trim() != entry.exercise.cue.trim()) GymCard {
                    Text("Твоя заметка", style = MaterialTheme.typography.titleMedium); Text(exercise.cue)
                }
                Text("Видео · нужен интернет", style = MaterialTheme.typography.titleLarge)
                if (entry.videos.isEmpty()) Text("Для этого упражнения пока нет видео.", color = Muted)
                entry.videos.forEachIndexed { index, video -> VideoCard(video, index + 1) }
                Text("Данные справочника: ${entry.attribution.author}", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (entry.attribution.licenseURL.isNotBlank()) WebLink("Лицензия: ${entry.attribution.license}", entry.attribution.licenseURL)
                if (entry.sourceURL.isNotBlank()) WebLink("Открыть источник", entry.sourceURL)
            }
            if (add != null) PrimaryAction("Добавить в тренировку", onClick = { add(exercise) })
        }
    }
}
@Composable private fun VideoCard(video: ExerciseVideo, number: Int) {
    var playing by rememberSaveable(video.id) { mutableStateOf(false) }
    GymCard {
        if (video.playback == "external") {
            WebLink("Смотреть на сайте автора", video.url)
            Text("Видео откроется в браузере на сайте автора.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else if (playing) {
            NativeVideo(video.url)
            TextButton(onClick = { playing = false }) { Text("Закрыть видео") }
        } else PrimaryAction("Смотреть технику · видео $number", onClick = { playing = true })
        Text("Видео: ${video.author}", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (video.playback != "external") WebLink(video.license, video.licenseURL)
        WebLink("Источник", video.sourceURL)
    }
}
@Composable private fun NativeVideo(url: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(url) { mutableStateOf(false) }
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(requireHttps(url).toString())); prepare(); playWhenReady = true } }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    if (failed) Text("Не удалось загрузить видео. Проверь интернет или открой источник.", color = MaterialTheme.colorScheme.error)
}
@Composable fun WebLink(title: String, url: String) {
    val context = LocalContext.current
    val safe = runCatching { requireHttps(url).toString() }.getOrNull()
    if (safe != null) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) } }) { Text(title) }
}
