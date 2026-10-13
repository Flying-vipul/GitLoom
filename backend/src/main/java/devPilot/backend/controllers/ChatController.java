package devPilot.backend.controllers;

import devPilot.backend.DTO.ChatMessageRequest;
import devPilot.backend.DTO.ChatMessageResponse;
import devPilot.backend.DTO.ChatSessionResponse;
import devPilot.backend.DTO.CreateChatSessionRequest;
import devPilot.backend.security.CurrentUser;
import devPilot.backend.services.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final CurrentUser currentUser;

    @PostMapping("/sessions")
    public ChatSessionResponse createSession(@Valid @RequestBody CreateChatSessionRequest request) {
        UUID userId = currentUser.require().getId();
        return chatService.createSession(userId, request);
    }

    @GetMapping("/sessions")
    public List<ChatSessionResponse> listSessions(@RequestParam UUID repositoryId) {
        UUID userId = currentUser.require().getId();
        return chatService.listSessions(userId, repositoryId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageResponse> getMessages(@PathVariable UUID sessionId) {
        UUID userId = currentUser.require().getId();
        return chatService.getMessages(userId, sessionId);
    }

    @PostMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamReply(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChatMessageRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        
        // Prevent reverse proxies (like Nginx on Render) from buffering the SSE stream
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        UUID userId = currentUser.require().getId();
        return chatService.streamReply(userId, sessionId, request.content());
    }
}
