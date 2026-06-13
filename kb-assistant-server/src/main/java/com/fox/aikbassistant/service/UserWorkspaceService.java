package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.ChatMessage;
import com.fox.aikbassistant.model.ConversationSession;
import com.fox.aikbassistant.model.UserProfile;

import java.util.List;

public interface UserWorkspaceService {

    UserProfile login(String email);

    UserProfile getUser(String email);

    List<ConversationSession> listSessions(String email);

    ConversationSession createSession(String email, String title);

    void deleteSession(String email, String sessionId);

    List<ChatMessage> listMessages(String email, String sessionId);

    void appendMessage(String email, String sessionId, ChatMessage message);
}
