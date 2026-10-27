package dev.podhod.training.data

import dev.podhod.core.archiveJson
import kotlin.test.*
import org.junit.Test

class AccountProfileTest {
    @Test fun `profile with missing optional fields displays login`() {
        val profile = archiveJson.decodeFromString<AccountProfile>("""{"id":"a","username":"owner","userProfileMetadata":{}}""")
        assertEquals("owner", profile.displayName)
    }
    @Test fun `full name is trimmed and preferred over login`() {
        assertEquals("Павел Иванов", AccountProfile("a", "owner", " Павел ", " Иванов ").displayName)
    }
    @Test fun `profile from another subject is rejected`() {
        assertFails { AccountDetails(AccountProfile("other"), emptyList(), "current") }
    }
    @Test fun `available telegram provider is not a connected account`() {
        val links = archiveJson.decodeFromString<List<AccountLink>>("""[{"providerAlias":"telegram","connected":false,"displayName":"Telegram"}]""")
        val details = AccountDetails(AccountProfile("a"), links, "a")
        assertFalse(details.telegram!!.connected)
        assertNull(details.telegram?.linkedUsername)
    }
    @Test fun `telegram identity is separate from other providers and duplicates are rejected`() {
        val details = AccountDetails(AccountProfile("a"), listOf(AccountLink("other", true, "different"), AccountLink("telegram", true, "12345")), "a")
        assertEquals("12345", details.telegram!!.linkedUsername)
        assertFails { AccountDetails(AccountProfile("a"), listOf(AccountLink("telegram", true), AccountLink("telegram", false)), "a") }
    }
}
