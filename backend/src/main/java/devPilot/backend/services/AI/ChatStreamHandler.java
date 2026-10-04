package devPilot.backend.services.AI;

import devPilot.backend.DTO.ChatMessageResponse;
import devPilot.backend.DTO.CitationDto;
import devPilot.backend.model.ChatMessage;
import devPilot.backend.model.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt
    ) {

        SseEmitter emitter =
                new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);

        StringBuilder fullReply = new StringBuilder();

        try {

            // Send the user's saved message first
            emitter.send(
                    SseEmitter.event()
                            .name("user_message")
                            .data(savedUserMessage)
            );

            ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()

                    // Every token from the LLM
                    .doOnNext(token ->
                            appendToken(
                                    emitter,
                                    fullReply,
                                    token
                            )
                    )

                    // Handle asynchronous streaming errors
                    .doOnError(err -> {

                        log.error(
                                "Chat stream error",
                                err
                        );

                        sendError(
                                emitter,
                                err
                        );
                    })

                    // Stream completed successfully
                    .doOnComplete(() ->
                            completeStream(
                                    emitter,
                                    sessionId,
                                    fullReply,
                                    citations
                            )
                    )

                    .subscribe();

        } catch (Exception ex) {

            log.error(
                    "Failed to start chat stream",
                    ex
            );

            sendError(
                    emitter,
                    ex
            );
        }

        return emitter;
    }

    private void appendToken(
            SseEmitter emitter,
            StringBuilder fullReply,
            String token
    ) {

        fullReply.append(token);

        try {

            emitter.send(
                    SseEmitter.event()
                            .name("token")
                            .data(
                                    token,
                                    MediaType.APPLICATION_JSON
                            )
            );

        } catch (Exception ex) {

            throw new IllegalStateException(
                    "Failed to send token",
                    ex
            );
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations
    ) {

        try {

            ChatMessage assistant =
                    chatMessageRepository.save(
                            ChatMessage.builder()
                                    .sessionId(sessionId)
                                    .role(MessageRole.ASSISTANT)
                                    .content(fullReply.toString())
                                    .citations(
                                            citationMapper.toJson(
                                                    citations
                                            )
                                    )
                                    .build()
                    );

            emitter.send(
                    SseEmitter.event()
                            .name("assistant_message")
                            .data(
                                    toMessageResponse(assistant)
                            )
            );

            emitter.send(
                    SseEmitter.event()
                            .name("done")
                            .data("[DONE]")
            );

            emitter.complete();

        } catch (Exception ex) {

            log.error(
                    "Failed to complete chat stream",
                    ex
            );

            sendError(
                    emitter,
                    ex
            );
        }
    }

    private void sendError(
            SseEmitter emitter,
            Throwable error
    ) {

        try {

            emitter.send(
                    SseEmitter.event()
                            .name("error")
                            .data(
                                    error.getMessage() != null
                                            ? error.getMessage()
                                            : "Chat stream failed"
                            )
            );

        } catch (Exception sendException) {

            log.warn(
                    "Could not send SSE error event: {}",
                    sendException.getMessage()
            );

        } finally {

            emitter.complete();
        }
    }

    private ChatMessageResponse toMessageResponse(
            ChatMessage message
    ) {

        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(
                        message.getCitations()
                ),
                message.getCreatedAt()
        );
    }
}