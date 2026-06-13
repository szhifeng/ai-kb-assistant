package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.DocumentInfo;
import com.fox.aikbassistant.model.DocumentDetail;
import com.fox.aikbassistant.model.IngestResult;
import com.fox.aikbassistant.model.UploadAudit;
import com.fox.aikbassistant.service.IngestService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestService ingestService;

    public DocumentController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public IngestResult upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(value = "uploaderEmail", required = false) String uploaderEmail) throws IOException {
        String name = file.getOriginalFilename();
        Resource res = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        return ingestService.ingest(res, name, file.getSize(), uploaderEmail);
    }

    @GetMapping
    public List<DocumentInfo> list() {
        return ingestService.listDocuments();
    }

    @GetMapping("/details")
    public List<DocumentDetail> details() {
        return ingestService.listDocumentDetails();
    }

    @GetMapping("/audit")
    public List<UploadAudit> audit(@RequestParam(value = "email", required = false) String email) {
        return ingestService.listUploadAudits(email);
    }

    @DeleteMapping("/{source}")
    public void delete(@PathVariable String source) {
        ingestService.deleteBySource(source);
    }
}
