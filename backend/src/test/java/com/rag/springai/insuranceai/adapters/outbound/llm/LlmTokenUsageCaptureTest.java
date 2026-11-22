package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FASE 27 (benchmarking): proves both real adapters actually carry the provider's reported token
 * counts out through the port - the whole reason they moved from {@code chatModel.call(Message,
 * Message)} (which returns a bare {@code String} and drops the metadata) to the {@code
 * ChatResponse}-returning overload.
 *
 * <p>Both the "with {@code Usage}" and "without {@code Usage}" shapes are exercised, because the
 * second is not hypothetical: {@code ChatResponseMetadata.getUsage()} is genuinely allowed to be
 * absent, and the failure mode it must not produce - {@code 0} tokens, i.e. a free-looking call -
 * is precisely the one that would corrupt a cost figure without ever throwing.
 *
 * <p>Still a pure unit test: the chat models are mocked, no OpenAI/Anthropic API is ever called.
 */
class LlmTokenUsageCaptureTest {

    private static final LlmPrompt PROMPT = new LlmPrompt("system", "Is water damage covered?",
            List.of("policy text"));

    private static ChatResponse responseWith(String text, Usage usage) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    @Nested
    class OpenAi {

        private final OpenAiChatModel chatModel = mock(OpenAiChatModel.class);

        private final OpenAiLlmAdapter adapter = new OpenAiLlmAdapter(chatModel);

        @Test
        void capturesThePromptAndCompletionTokensReportedByTheProvider() {
            when(chatModel.call(any(Prompt.class)))
                    .thenReturn(responseWith("Water damage is covered.", new DefaultUsage(1_200, 340)));

            LlmCompletion completion = adapter.complete(PROMPT);

            assertEquals("Water damage is covered.", completion.text());
            assertTrue(completion.hasTokenUsage());
            assertEquals(1_200, completion.inputTokens());
            assertEquals(340, completion.outputTokens());
            assertEquals(1_540, completion.totalTokens());
        }

        @Test
        void reportsNoTokenUsageRatherThanZerosWhenTheResponseCarriesNoUsage() {
            when(chatModel.call(any(Prompt.class))).thenReturn(responseWith("Water damage is covered.", null));

            LlmCompletion completion = adapter.complete(PROMPT);

            assertEquals("Water damage is covered.", completion.text());
            assertFalse(completion.hasTokenUsage());
            assertNull(completion.inputTokens());
            assertNull(completion.outputTokens());
        }

        /**
         * The real-world shape of "no usage", and the trap this test exists to keep shut: Spring
         * AI's default {@code ChatResponseMetadata} does not leave usage null, it installs an
         * {@code EmptyUsage} that answers {@code 0} to both token questions. Read naively that
         * becomes a completion claiming a genuine, free, zero-token call.
         */
        @Test
        void reportsNoTokenUsageWhenTheResponseCarriesOnlyTheDefaultEmptyUsage() {
            when(chatModel.call(any(Prompt.class)))
                    .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

            LlmCompletion completion = adapter.complete(PROMPT);

            assertFalse(completion.hasTokenUsage(), "EmptyUsage's 0/0 must not be mistaken for a measured 0 tokens");
            assertNull(completion.inputTokens());
        }

        @Test
        void stillSendsTheSystemInstructionsAndTheFormattedUserMessageAsTwoSeparateMessages() {
            // The switch to the Prompt-based overload must not have quietly collapsed the
            // system/user split that keeps retrieved context out of the instruction channel.
            when(chatModel.call(any(Prompt.class))).thenReturn(responseWith("answer", null));

            adapter.complete(PROMPT);

            var captor = forClass(Prompt.class);
            verify(chatModel).call(captor.capture());
            List<org.springframework.ai.chat.messages.Message> messages = captor.getValue().getInstructions();
            assertEquals(2, messages.size());
            assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
            assertEquals("system", messages.get(0).getText());
            assertEquals(MessageType.USER, messages.get(1).getMessageType());
            assertTrue(messages.get(1).getText().contains("Is water damage covered?"));
            assertTrue(messages.get(1).getText().contains("policy text"));
        }
    }

    @Nested
    class Anthropic {

        private final AnthropicChatModel chatModel = mock(AnthropicChatModel.class);

        private final AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        @Test
        void capturesThePromptAndCompletionTokensReportedByTheProvider() {
            when(chatModel.call(any(Prompt.class)))
                    .thenReturn(responseWith("Water damage is covered.", new DefaultUsage(90, 12)));

            LlmCompletion completion = adapter.complete(PROMPT);

            assertEquals("Water damage is covered.", completion.text());
            assertEquals(90, completion.inputTokens());
            assertEquals(12, completion.outputTokens());
        }

        @Test
        void reportsNoTokenUsageRatherThanZerosWhenTheResponseCarriesNoUsage() {
            when(chatModel.call(any(Prompt.class))).thenReturn(responseWith("Water damage is covered.", null));

            assertFalse(adapter.complete(PROMPT).hasTokenUsage());
        }
    }
}
