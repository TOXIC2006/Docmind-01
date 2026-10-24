package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.DTO.DocumentDto;
import com.lcdw.springai.docmind01.Entity.DocumentMetadata;
import com.lcdw.springai.docmind01.Entity.Document_Status;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.batch.BatchProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DocumentServiceimpl implements DocumentService {

   @Autowired
   private DocumnetMetadatarepo documnetMetadatarepo;

   @Autowired
   private JdbcTemplate jdbcTemplate;

   @Autowired
    private ParseService parseService;
   @Autowired
   private IngestionService ingestionService;



    @Override
    public DocumentDto   uploadDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "document";

        String contentType = file.getContentType() != null
                ? file.getContentType()
                : "application/octet-stream";

// Document metadata create
        DocumentMetadata documentMetadata = DocumentMetadata.builder()
                .filename(fileName)
                .fileSize(file.getSize())
                .contentType(contentType)
                .createdAt(LocalDateTime.now())
                .status(Document_Status.processing)
                .build();

// Save the document metadata
        DocumentMetadata savedDocumentMetadata =
                documnetMetadatarepo.save(documentMetadata);

// Parse the file
        List<Document> parsedDocs = parseService.parse(file);

// Ingest service
        int chunksCreated = ingestionService.ingest(documentMetadata, parsedDocs);

        return DocumentDto.builder()
                .id(documentMetadata.getId())
                .fileName(documentMetadata.getFilename())
                .fileSize(documentMetadata.getFileSize())
                .chunksCreated(chunksCreated)
                .status(documentMetadata.getStatus())
                .message("Document successfully processed and indexed.")
                .build();
    }
}
