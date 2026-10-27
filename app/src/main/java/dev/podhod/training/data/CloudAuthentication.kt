package dev.podhod.training.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.podhod.core.requireHttps
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AuthenticationStatus(val signedIn: Boolean = false, val busy: Boolean = false, val message: String = "Вход не выполнен")
class CloudAuthentication(context: Context, val serverURL: String) {
    private val credentials = CredentialStore(context)
    private val service = AuthorizationService(context)
    private var authState = AuthState()
    private var pendingState: String? = null
    private val refreshMutex = Mutex()
    private val mutableStatus = MutableStateFlow(AuthenticationStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile var generation = UUID.randomUUID()
        private set
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
    init {
        try {
            credentials.read()?.let {
                val saved = JSONObject(it)
                require(saved.getString("server") == serverURL)
                authState = AuthState.jsonDeserialize(saved.getString("auth"))
                pendingState = saved.optString("pending").takeIf { state -> state.isNotEmpty() }
                mutableStatus.value = AuthenticationStatus(authState.isAuthorized, message = if (authState.isAuthorized) "Вход выполнен" else "Вход не выполнен")
            }
        } catch (_: Exception) { mutableStatus.value = AuthenticationStatus(message = "Не удалось прочитать ключ входа. Выполни вход заново.") }
    }
    private fun persist() = credentials.write(JSONObject().put("server", serverURL).put("auth", authState.jsonSerializeString()).put("pending", pendingState ?: "").toString())
    suspend fun authorizationIntent(): Intent = authorizationIntent(null)
    suspend fun telegramAuthorizationIntent(): Intent = authorizationIntent("telegram")

    suspend fun accountManagementURL(): Uri = withContext(Dispatchers.IO) {
        val config = publicJSON(serverURL.trimEnd('/') + "/v1/config?platform=android")
        val issuer = requireHttps(config.getString("issuer")).toString().trimEnd('/')
        Uri.parse("$issuer/account/#/linked-accounts")
    }
    suspend fun verifiedAccountID(): String {
        val operation = generation
        val api = CloudAPI(serverURL, { accessToken() }, { generation == operation && status.value.signedIn })
        return api.request("/v1/profile")["id"]!!.let { dev.podhod.core.archiveJson.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), it) }
    }

    private suspend fun authorizationIntent(identityProvider: String?): Intent {
        check(!mutableStatus.value.busy)
        mutableStatus.value = mutableStatus.value.copy(busy = true)
        val operation = generation
        try {
            val (configuration, scopes) = withContext(Dispatchers.IO) {
                val config = publicJSON(serverURL.trimEnd('/') + "/v1/config?platform=android")
                require(config.getString("clientID") == "podhod-android") { "Сервер ещё не настроен для Android." }
                val issuer = requireHttps(config.getString("issuer")).toString()
                val discovery = publicJSON(issuer.trimEnd('/') + "/.well-known/openid-configuration")
                require(discovery.getString("issuer") == issuer)
                val methods = discovery.getJSONArray("code_challenge_methods_supported")
                require((0 until methods.length()).any { methods.getString(it) == "S256" })
                val authURL = requireHttps(discovery.getString("authorization_endpoint"))
                val tokenURL = requireHttps(discovery.getString("token_endpoint"))
                val requested = config.getJSONArray("scopes").let { array -> (0 until array.length()).map { array.getString(it) } }
                require("openid" in requested && requested.all { it.isNotBlank() && it.none(Char::isWhitespace) })
                AuthorizationServiceConfiguration(Uri.parse(authURL.toString()), Uri.parse(tokenURL.toString())) to requested
            }
            check(operation == generation)
            val builder = AuthorizationRequest.Builder(configuration, "podhod-android", ResponseTypeValues.CODE, REDIRECT)
                .setScopes(scopes).setPrompt("login")
            if (identityProvider != null) builder.setAdditionalParameters(mapOf("kc_idp_hint" to identityProvider))
            val request = builder.build()
            pendingState = request.state
            persist()
            return service.getAuthorizationRequestIntent(request)
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(message = "Не удалось начать вход. Проверь соединение и настройку Android на сервере.")
            throw error
        } finally { mutableStatus.value = mutableStatus.value.copy(busy = false) }
    }
    suspend fun complete(intent: Intent?) {
        if (intent == null) return
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        val operation = generation
        require(response != null && error == null && pendingState != null && response.state == pendingState &&
            response.request.clientId == "podhod-android" && response.request.redirectUri == REDIRECT) { "Вход отменён или ответ входа недействителен." }
        mutableStatus.value = mutableStatus.value.copy(busy = true)
        try {
            authState.update(response, null)
            val result: TokenResponse = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { token, failure ->
                    if (continuation.isActive) {
                        if (token != null && failure == null) continuation.resume(token)
                        else continuation.resumeWithException(failure ?: IOException("Не удалось получить ключ входа."))
                    }
                }
            }
            check(operation == generation)
            require(result.accessToken?.isNotBlank() == true && result.tokenType.equals("Bearer", ignoreCase = true))
            authState.update(result, null)
            pendingState = null
            persist()
            mutableStatus.value = AuthenticationStatus(true, message = "Вход выполнен")
        } finally { mutableStatus.value = mutableStatus.value.copy(busy = false) }
    }
    suspend fun accessToken(): String = refreshMutex.withLock {
        check(authState.isAuthorized)
        val operation = generation
        val token: String = suspendCancellableCoroutine { continuation ->
            authState.performActionWithFreshTokens(service) { access, _, error ->
                if (continuation.isActive) {
                    if (error == null && access != null) continuation.resume(access)
                    else continuation.resumeWithException(error ?: IOException("Нужно выполнить вход заново."))
                }
            }
        }
        check(operation == generation)
        persist()
        token
    }
    fun signOut() {
        generation = UUID.randomUUID()
        pendingState = null
        authState = AuthState()
        credentials.clear()
        mutableStatus.value = AuthenticationStatus(message = "Выполнен выход на этом устройстве")
    }
    private fun publicJSON(url: String): JSONObject {
        requireHttps(url)
        return http.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "Конфигурация входа недоступна." }
            JSONObject(it.body?.string() ?: throw IOException("Пустая конфигурация."))
        }
    }
    companion object { val REDIRECT: Uri = Uri.parse("dev.podhod.training://oauth/callback") }
}
