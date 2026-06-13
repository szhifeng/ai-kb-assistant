package com.fox.aikbassistant.model;

import java.util.List;

public record ChatAnswer(String answer, List<Citation> citations, String conversationId) {}
