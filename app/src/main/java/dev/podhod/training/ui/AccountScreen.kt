package dev.podhod.training.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.podhod.training.TrainingViewModel
import kotlinx.coroutines.launch

@Composable fun AccountScreen(model: TrainingViewModel, back: () -> Unit) {
    val auth by model.auth.status.collectAsStateWithLifecycle()
    val sync by model.sync.status.collectAsStateWithLifecycle()
    val account by model.auth.accountProfile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, auth.signedIn) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (auth.signedIn) model.auth.refreshProfile()
            kotlinx.coroutines.awaitCancellation()
        }
    }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.completeLogin(it.data) }
    FullScreen("Аккаунт и синхронизация", back) { padding ->
        ScreenColumn(Modifier.padding(padding)) {
            ScreenHeading("Твои данные", "Программа и история доступны без сети.")
            GymCard {
                Text("Аккаунт", style = MaterialTheme.typography.titleLarge)
                Text(auth.message)
                Text(java.net.URI(model.auth.serverURL).host, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (auth.busy) CircularProgressIndicator()
                if (auth.signedIn) {
                    account.details?.let { details ->
                        Text(details.profile.displayName, style = MaterialTheme.typography.titleLarge)
                        if (details.profile.username != null && details.profile.username != details.profile.displayName)
                            Text("Логин: ${details.profile.username}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(if (details.telegram?.connected == true) "Telegram подключён" else "Telegram не подключён",
                            color = if (details.telegram?.connected == true) Lime else Muted)
                        if (details.telegram?.connected == true) details.telegram?.linkedUsername?.takeIf { it.isNotBlank() }?.let {
                            Text("Аккаунт Telegram: $it", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (account.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    account.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { scope.launch { model.auth.refreshProfile() } }, enabled = !account.loading) { Text("Обновить профиль") }
                    TextButton(onClick = {
                        scope.launch { try { context.startActivity(Intent(Intent.ACTION_VIEW, model.auth.accountManagementURL())) }
                        catch (_: Exception) { model.report("Не удалось открыть управление аккаунтом.") } }
                    }, enabled = !auth.busy) { Text(if (account.details?.telegram?.connected == true) "Управлять привязкой Telegram" else "Привязать Telegram к аккаунту") }
                    account.details?.takeIf { it.telegram?.connected != true }?.let {
                        Text("В кабинете подтверди аккаунт ${it.profile.username ?: it.profile.displayName}, затем открой привязку Telegram.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = model::signOut, enabled = !auth.busy) { Text("Отключить аккаунт", color = MaterialTheme.colorScheme.error) }
                } else {
                    PrimaryAction("Войти через Telegram", enabled = !auth.busy, onClick = {
                        scope.launch { try { login.launch(model.telegramLoginIntent()) } catch (_: Exception) { model.report("Не удалось открыть вход через Telegram. Проверь соединение.") } }
                    })
                    OutlinedButton(onClick = {
                        scope.launch { try { login.launch(model.loginIntent()) } catch (_: Exception) { model.report("Не удалось открыть вход. Проверь соединение.") } }
                    }, enabled = !auth.busy, modifier = Modifier.fillMaxWidth()) { Text("Войти по логину") }
                    Text("Уже пользуешься «Подходом»? Войди прежним способом и привяжи Telegram в настройках аккаунта — тогда история останется в том же аккаунте.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("Вход откроется в системном браузере. Выход сохраняет архив аккаунта отдельно и открывает гостевой режим. Затем можно войти в другой аккаунт.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            GymCard {
                Text("Синхронизация", style = MaterialTheme.typography.titleLarge)
                Text(sync.message)
                if (sync.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                PrimaryAction("Синхронизировать сейчас", enabled = auth.signedIn && !sync.busy && sync.conflict == null, onClick = {
                    model.synchronize()
                    scope.launch { model.auth.refreshProfile() }
                })
                sync.conflict?.let { conflict ->
                    Text(conflict.message, color = MaterialTheme.colorScheme.error)
                    if (conflict.localAllowed) TextButton(onClick = { model.resolve(false) }, enabled = !sync.busy && auth.signedIn) { Text("Оставить версию телефона") }
                    if (conflict.serverAllowed) TextButton(onClick = { model.resolve(true) }, enabled = !sync.busy && auth.signedIn) { Text("Использовать версию сервера") }
                    if (!conflict.localAllowed && !conflict.serverAllowed) Text("Автоматический выбор недоступен: ни одна версия не сохраняет все записи. Обе версии защищены.", color = Muted)
                }
                Text("У каждого аккаунта отдельный архив. При конфликте обе версии сохраняются перед выбором. Завершённая история неизменна.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
