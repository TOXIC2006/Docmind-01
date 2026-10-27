package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.DTO.DocumentDto;
import com.lcdw.springai.docmind01.Entity.DocumentMetadata;
import com.lcdw.springai.docmind01.Entity.Document_Status;
import com.lcdw.springai.docmind01.Execption.Document_EXE;
import com.lcdw.springai.docmind01.Execption.ResourceNotFound_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

   @Autowired
   private VectorStore vectorStore;



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
        documnetMetadatarepo.save(documentMetadata);

        try {
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
        } catch (Document_EXE e) {
            // a parse failure never reaches IngestionService, so nothing would
            // mark this row failed - do it here and rethrow for the caller
            documentMetadata.setStatus(Document_Status.failed);
            documentMetadata.setErrorMessage(e.getMessage());
            documnetMetadatarepo.save(documentMetadata);
            throw e;
        }
    }

    @Override
    public List<DocumentDto> uploadDocuments(List<MultipartFile> files) {
        List<DocumentDto> results = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            String fileName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename()
                    : "document";

            try {
                results.add(uploadDocument(file));
            } catch (Document_EXE e) {
                results.add(DocumentDto.builder()
                        .fileName(fileName)
                        .fileSize(file.getSize())
                        .status(Document_Status.failed)
                        .chunksCreated(0)
                        .message(e.getMessage())
                        .build());
            }
        }

        return results;
    }

    @Override
    public List<DocumentDto> listDocuments() {
        return documnetMetadatarepo.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public DocumentDto getDocument(String id) {
        DocumentMetadata metadata = documnetMetadatarepo.findById(id)
                .orElseThrow(() -> new ResourceNotFound_EXE("Document not found with id: " + id));

        return toDto(metadata);
    }

    @Override
    public void deleteDocument(String id) {
        DocumentMetadata metadata = documnetMetadatarepo.findById(id)
                .orElseThrow(() -> new ResourceNotFound_EXE("Document not found with id: " + id));

        // vectors first, otherwise /search would keep returning chunks of a deleted document
        vectorStore.delete(new FilterExpressionBuilder()
                .eq("documentId", id)
                .build());

        documnetMetadatarepo.delete(metadata);
    }

    private DocumentDto toDto(DocumentMetadata metadata) {
        return DocumentDto.builder()
                .id(metadata.getId())
                .fileName(metadata.getFilename())
                .fileSize(metadata.getFileSize())
                .status(metadata.getStatus())
                .chunksCreated(metadata.getTotalChunks())
                .message(metadata.getErrorMessage())
                .build();
    }
}
