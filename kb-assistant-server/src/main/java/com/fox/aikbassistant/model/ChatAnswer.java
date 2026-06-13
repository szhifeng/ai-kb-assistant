package com.fox.aikbassistant.model;

import java.util.List;

public record ChatAnswer(String answer, String reasoning, List<Citation> citations, String conversationId) {}
