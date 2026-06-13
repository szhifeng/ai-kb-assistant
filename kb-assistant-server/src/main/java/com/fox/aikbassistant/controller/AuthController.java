package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.EmailLoginRequest;
import com.fox.aikbassistant.model.UserProfile;
import com.fox.aikbassistant.service.UserWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserWorkspaceService userWorkspaceService;

    public AuthController(UserWorkspaceService userWorkspaceService) {
        this.userWorkspaceService = userWorkspaceService;
    }

    @PostMapping("/email/login")
    public UserProfile login(@RequestBody EmailLoginRequest request) {
        return userWorkspaceService.login(request.email());
    }

    @GetMapping("/me")
    public UserProfile me(@RequestParam String email) {
        return userWorkspaceService.getUser(email);
    }
}
