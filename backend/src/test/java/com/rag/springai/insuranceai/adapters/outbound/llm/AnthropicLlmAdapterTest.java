package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tested with a mocked {@code AnthropicChatModel} - never calls the real Anthropic API
 * (brief section 16/18).
 *
 * <p>FASE 27: these stubs moved from {@code call(Message, Message)} to {@code call(Prompt)}
 * because the adapter itself did, to reach the token usage the {@code String}-returning overload
 * discards - every assertion below is unchanged. Token capture itself is covered by
 * {@code LlmTokenUsageCaptureTest}.
 */
class AnthropicLlmAdapterTest {

    @Test
    void returnsTheChatModelsResponseAsTheCompletionText() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Water damage is covered.")))));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);
        LlmCompletion completion = adapter
                .complete(new LlmPrompt("system", "Is water damage covered?", List.of("policy text")));

        assertEquals("Water damage is covered.", completion.text());
    }

    @Test
    void wrapsAChatModelFailureAsATransientProcessingException() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }

    @Test
    void wrapsA4xxChatModelFailureAsAPermanentProcessingException() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null,
                        null));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(PermanentProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }

    @Test
    void wrapsA429RateLimitFailureAsATransientProcessingExceptionNotPermanent() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                        null, null));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }

    @Test
    void wrapsA408RequestTimeoutFailureAsATransientProcessingExceptionNotPermanent() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.REQUEST_TIMEOUT, "Request Timeout", null, null,
                        null));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }
}
