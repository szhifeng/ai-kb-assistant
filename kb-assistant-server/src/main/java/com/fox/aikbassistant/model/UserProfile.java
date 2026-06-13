package com.fox.aikbassistant.model;

import java.time.Instant;

public record UserProfile(String email, String displayName, Instant signedInAt) {}
