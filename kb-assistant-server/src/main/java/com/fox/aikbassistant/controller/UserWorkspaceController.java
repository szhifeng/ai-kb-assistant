package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.ChatMessage;
import com.fox.aikbassistant.model.ConversationSession;
import com.fox.aikbassistant.model.CreateSessionRequest;
import com.fox.aikbassistant.model.UploadAudit;
import com.fox.aikbassistant.service.IngestService;
import com.fox.aikbassistant.service.UserWorkspaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users/{email}")
public class UserWorkspaceController {

    private final UserWorkspaceService userWorkspaceService;
    private final IngestService ingestService;

    public UserWorkspaceController(UserWorkspaceService userWorkspaceService, IngestService ingestService) {
        this.userWorkspaceService = userWorkspaceService;
        this.ingestService = ingestService;
    }

    @GetMapping("/sessions")
    public List<ConversationSession> listSessions(@PathVariable String email) {
        return userWorkspaceService.listSessions(email);
    }

    @PostMapping("/sessions")
    public ConversationSession createSession(@PathVariable String email, @RequestBody CreateSessionRequest request) {
        return userWorkspaceService.createSession(email, request.title());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable String email, @PathVariable String sessionId) {
        userWorkspaceService.deleteSession(email, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> listMessages(@PathVariable String email, @PathVariable String sessionId) {
        return userWorkspaceService.listMessages(email, sessionId);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public void appendMessage(@PathVariable String email,
                              @PathVariable String sessionId,
                              @RequestBody ChatMessage message) {
        userWorkspaceService.appendMessage(email, sessionId, message);
    }

    @GetMapping("/uploads")
    public List<UploadAudit> uploads(@PathVariable String email) {
        return ingestService.listUploadAudits(email);
    }
}
