package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.DocumentInfo;
import com.fox.aikbassistant.model.IngestResult;
import com.fox.aikbassistant.model.DocumentDetail;
import com.fox.aikbassistant.model.UploadAudit;
import org.springframework.core.io.Resource;

import java.util.List;

public interface IngestService {

    IngestResult ingest(Resource resource, String source);

    IngestResult ingest(Resource resource, String source, long size, String uploaderEmail);

    List<DocumentInfo> listDocuments();

    List<DocumentDetail> listDocumentDetails();

    List<UploadAudit> listUploadAudits(String email);

    void deleteBySource(String source);
}
