package com.fox.aikbassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fox.aikbassistant.util.JsonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return JsonUtils.objectMapper();
    }
}
