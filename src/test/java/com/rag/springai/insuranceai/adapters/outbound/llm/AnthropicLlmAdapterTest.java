package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.Message;
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
 */
class AnthropicLlmAdapterTest {

    @Test
    void returnsTheChatModelsResponseAsTheCompletionText() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class))).thenReturn("Water damage is covered.");

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);
        LlmCompletion completion = adapter
                .complete(new LlmPrompt("system", "Is water damage covered?", List.of("policy text")));

        assertEquals("Water damage is covered.", completion.text());
    }

    @Test
    void wrapsAChatModelFailureAsATransientProcessingException() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class))).thenThrow(new RuntimeException("timeout"));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }

    @Test
    void wrapsA4xxChatModelFailureAsAPermanentProcessingException() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null,
                        null));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(PermanentProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }

    @Test
    void wrapsA429RateLimitFailureAsATransientProcessingExceptionNotPermanent() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                        null, null));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }

    @Test
    void wrapsA408RequestTimeoutFailureAsATransientProcessingExceptionNotPermanent() {
        AnthropicChatModel chatModel = mock(AnthropicChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.REQUEST_TIMEOUT, "Request Timeout", null, null,
                        null));

        AnthropicLlmAdapter adapter = new AnthropicLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }
}
