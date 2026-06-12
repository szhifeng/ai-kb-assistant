package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.IngestResult;
import com.fox.aikbassistant.service.IngestService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestService ingestService;

    public DocumentController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public IngestResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        Resource res = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        return ingestService.ingest(res, name);
    }
}
