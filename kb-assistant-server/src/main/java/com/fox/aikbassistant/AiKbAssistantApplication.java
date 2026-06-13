package com.fox.aikbassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiKbAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiKbAssistantApplication.class, args);
    }

}
