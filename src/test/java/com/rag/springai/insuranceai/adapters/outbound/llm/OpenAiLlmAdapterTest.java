package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tested with a mocked {@code OpenAiChatModel} - never calls the real OpenAI API (brief
 * section 16/18).
 */
class OpenAiLlmAdapterTest {

    @Test
    void returnsTheChatModelsResponseAsTheCompletionText() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class))).thenReturn("Water damage is covered.");

        OpenAiLlmAdapter adapter = new OpenAiLlmAdapter(chatModel);
        LlmCompletion completion = adapter
                .complete(new LlmPrompt("system", "Is water damage covered?", List.of("policy text")));

        assertEquals("Water damage is covered.", completion.text());
    }

    @Test
    void wrapsAChatModelFailureAsATransientProcessingException() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Message.class), any(Message.class))).thenThrow(new RuntimeException("timeout"));

        OpenAiLlmAdapter adapter = new OpenAiLlmAdapter(chatModel);

        assertThrows(TransientProcessingException.class,
                () -> adapter.complete(new LlmPrompt("system", "question", List.of())));
    }
}
