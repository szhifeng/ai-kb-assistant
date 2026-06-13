package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.ChatAnswer;
import com.fox.aikbassistant.model.RagStreamResult;

public interface RagChatService {

    RagStreamResult stream(String question);

    RagStreamResult stream(String question, String conversationId);

    RagStreamResult stream(String question, String conversationId, String model);

    RagStreamResult stream(String question, String conversationId, String model, boolean webSearchEnabled);

    ChatAnswer call(String question, String conversationId, String model);

    ChatAnswer call(String question, String conversationId, String model, boolean webSearchEnabled);
}
