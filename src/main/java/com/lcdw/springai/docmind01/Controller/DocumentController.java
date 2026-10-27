package com.lcdw.springai.docmind01.Controller;

import com.lcdw.springai.docmind01.DTO.DocumentDto;
import com.lcdw.springai.docmind01.Execption.API_Respone;
import com.lcdw.springai.docmind01.Service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;


@RestController
@RequestMapping("/api/v1")
@Tag(name = "DocMind API", description = "API for DocMind application")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @PostMapping(value = "/upload_document",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document",
            description = "Parses the uploaded file, chunks it and indexes the chunks into the pgvector store")
    public ResponseEntity<DocumentDto> uploadDocument(@RequestParam("file") MultipartFile file) {
        DocumentDto documentDto = documentService.uploadDocument(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(documentDto);
    }

    @PostMapping(value = "/upload_documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload multiple documents",
            description = "Uploads several files at once. Each file is processed independently - a broken file fails on its own without stopping the rest.")
    public ResponseEntity<List<DocumentDto>> uploadDocuments(@RequestParam("files") List<MultipartFile> files) {
        List<DocumentDto> results = documentService.uploadDocuments(files);
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    @GetMapping("/documents")
    @Operation(summary = "List all documents",
            description = "Returns the metadata of every uploaded document, including failed ones")
    public ResponseEntity<List<DocumentDto>> listDocuments() {
        return ResponseEntity.ok(documentService.listDocuments());
    }

    @GetMapping("/documents/{id}")
    @Operation(summary = "Get a document by id")
    public ResponseEntity<DocumentDto> getDocument(@PathVariable("id") String id) {
        return ResponseEntity.ok(documentService.getDocument(id));
    }

    @DeleteMapping("/documents/{id}")
    @Operation(summary = "Delete a document",
            description = "Deletes the document metadata and all of its vectors from the pgvector store")
    public ResponseEntity<API_Respone> deleteDocument(@PathVariable("id") String id) {
        documentService.deleteDocument(id);
        return ResponseEntity.ok(new API_Respone(
                true,
                "Document deleted: " + id,
                200,
                LocalDateTime.now()
        ));
    }

}
