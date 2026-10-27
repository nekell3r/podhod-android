package dev.podhod.training.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.podhod.core.*
import java.util.Locale

val Lime = Color(0xFFCBF36B)
val Background = Color(0xFF10120F)
val CardColor = Color(0xFF1D211A)
val Muted = Color(0xFFA5AC9E)
@Composable fun PodhodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, onPrimary = Background, background = Background,
        surface = Background, surfaceContainer = CardColor, onSurface = Color(0xFFF4F5EF), secondary = Lime, outline = Muted), content = content)
}
@Composable fun GymCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = CardColor) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}
@Composable fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 20.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
}
@Composable fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 39.sp)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FullScreen(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }, actions = actions) }) { padding -> content(padding) }
}
@Composable fun DataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = Muted)
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
    }
}
@Composable fun VolumeSummary(volume: PlannedVolume) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Нагрузка по мышцам", style = MaterialTheme.typography.titleMedium)
        Text("Прямые подходы × 1 + косвенные × 0,5", color = Muted, style = MaterialTheme.typography.bodySmall)
        volume.muscles.forEach { m -> DataRow(m.name, "${m.direct} + ${m.indirect} × 0,5 = ${number(m.weighted)}") }
        if (volume.muscles.isEmpty()) Text("Добавь упражнения с разметкой мышц.", color = Muted)
        if (volume.unmappedExercises > 0) Text("Без разметки: ${volume.unmappedExercises} упр., ${volume.unmappedSets} подх. Они не включены в расчёт.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("Плановая оценка. Все подходы считаются рабочими. Каталог: ${volume.catalogVersion}.", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
fun number(value: Double) = String.format(Locale.forLanguageTag("ru-RU"), if (value % 1 == 0.0) "%.0f" else "%.1f", value)

@Composable fun ExerciseArt(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(170.dp).background(CardColor, RoundedCornerShape(18.dp)).semantics { contentDescription = "Иллюстрация упражнения" }) {
        val scale = minOf(size.width / 300, size.height / 210)
        val left = (size.width - 300 * scale) / 2
        val top = (size.height - 210 * scale) / 2
        fun point(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
        fun line(points: List<Pair<Float, Float>>, color: Color = Color(0xFFEAEDE5), width: Float = 9f) {
            val path = Path().apply { moveTo(point(points[0].first, points[0].second).x, point(points[0].first, points[0].second).y); points.drop(1).forEach { val p = point(it.first, it.second); lineTo(p.x, p.y) } }
            drawPath(path, color, style = Stroke(width * scale, cap = StrokeCap.Round))
        }
        fun bar(x: Float, y: Float, span: Float = 100f) {
            line(listOf(x - span / 2 to y, x + span / 2 to y), Lime, 4f)
            for (offset in listOf(-span / 2 + 8, span / 2 - 8)) line(listOf(x + offset to y - 12, x + offset to y + 12), Lime, 9f)
        }
        for (x in 30..270 step 30) line(listOf(x.toFloat() to 20f, x.toFloat() to 190f), Color.White.copy(alpha = .035f), 1f)
        line(listOf(25f to 188f, 275f to 188f), Muted.copy(alpha = .3f), 1f)
        when (kind) {
            "bench" -> {
                line(listOf(55f to 142f, 230f to 142f), Muted.copy(alpha = .4f), 8f)
                drawCircle(Color.White, 13 * scale, point(77f, 118f))
                line(listOf(98f to 124f, 169f to 130f, 196f to 151f, 203f to 181f, 227f to 181f))
                line(listOf(110f to 124f, 133f to 102f, 133f to 60f)); bar(133f, 56f)
            }
            "squat", "lunge", "deadlift" -> {
                drawCircle(Color.White, 13 * scale, point(145f, 48f))
                line(listOf(141f to 69f, 128f to 115f, 164f to 139f, 147f to 182f, 173f to 182f))
                line(listOf(128f to 115f, 94f to 142f, 93f to 182f, 72f to 182f), Muted)
                line(listOf(138f to 76f, 111f to 86f, 102f to 63f)); bar(145f, 69f)
            }
            "pullup", "pulldown" -> {
                line(listOf(60f to 181f, 60f to 26f, 240f to 26f, 240f to 181f), Muted.copy(alpha = .4f), 4f)
                drawCircle(Color.White, 13 * scale, point(150f, 75f))
                line(listOf(150f to 96f, 150f to 140f, 121f to 176f)); line(listOf(150f to 140f, 180f to 176f))
                line(listOf(146f to 101f, 116f to 88f, 103f to 43f)); line(listOf(155f to 101f, 184f to 88f, 197f to 43f)); bar(150f, 39f, 128f)
            }
            else -> {
                drawCircle(Color.White, 13 * scale, point(150f, 51f))
                line(listOf(150f to 74f, 150f to 126f, 126f to 181f)); line(listOf(150f to 126f, 177f to 181f))
                line(listOf(141f to 85f, 116f to 113f, 107f to 86f)); line(listOf(159f to 85f, 184f to 113f, 193f to 86f))
                if (kind != "neck") { bar(105f, 80f, 36f); bar(195f, 80f, 36f) }
            }
        }
    }
}
