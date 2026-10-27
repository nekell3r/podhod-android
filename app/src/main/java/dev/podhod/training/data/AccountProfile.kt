package dev.podhod.training.data

import kotlinx.serialization.Serializable

@Serializable data class AccountProfile(val id: String, val username: String? = null, val firstName: String? = null, val lastName: String? = null) {
    val displayName: String get() = listOfNotNull(firstName, lastName).map(String::trim).filter(String::isNotEmpty)
        .joinToString(" ").ifEmpty { username?.trim().orEmpty().ifEmpty { "Аккаунт" } }
}

@Serializable data class AccountLink(val providerAlias: String, val connected: Boolean, val linkedUsername: String? = null)

data class AccountDetails(val profile: AccountProfile, val links: List<AccountLink>, val expectedAccountID: String) {
    init {
        require(expectedAccountID.isNotEmpty() && profile.id == expectedAccountID) { "Профиль относится к другому аккаунту." }
        require(links.count { it.providerAlias == "telegram" } <= 1) { "Список привязок недействителен." }
    }
    val telegram get() = links.firstOrNull { it.providerAlias == "telegram" }
}

data class AccountProfileState(val details: AccountDetails? = null, val loading: Boolean = false, val error: String? = null)
