package com.santamota.reminder.nlu

import com.google.common.truth.Truth.assertThat
import com.santamota.reminder.domain.Intent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LlmAdapterTest {

    @Test
    fun `FakeLlmAdapter returns canned intent`() = runTest {
        val fake = FakeLlmAdapter(canned = { Intent.Chat("ok: $it") })
        val result = fake.resolveIntent("hello there", ChatContext())
        assertThat(result).isInstanceOf(Intent.Chat::class.java)
        assertThat((result as Intent.Chat).text).contains("hello there")
    }

    @Test
    fun `FakeLlmAdapter composeResponse uses default template`() = runTest {
        val fake = FakeLlmAdapter()
        val text = fake.composeResponse(
            ResponsePrompt.Confirmation("scheduled lunch at 1 PM"),
            ChatContext(),
        )
        assertThat(text).contains("scheduled lunch at 1 PM")
    }

    @Test
    fun `intentPrompt embeds active reminders and timezone`() {
        val ctx = ChatContext(
            activeReminderTitles = listOf("lunch", "medicine"),
            userTimezone = "America/Los_Angeles",
        )
        val prompt = PromptTemplates.intentPrompt("remind me before lunch", ctx)
        assertThat(prompt).contains("lunch")
        assertThat(prompt).contains("medicine")
        assertThat(prompt).contains("America/Los_Angeles")
    }
}
