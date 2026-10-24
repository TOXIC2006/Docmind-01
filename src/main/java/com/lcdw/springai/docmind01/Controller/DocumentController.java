package com.lcdw.springai.docmind01.Controller;

import com.lcdw.springai.docmind01.DTO.DocumentDto;
import com.lcdw.springai.docmind01.Service.DocumentServiceimpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


@RestController
@RequestMapping("/api/v1")
@Tag(name = "DocMind API", description = "API for DocMind application")
public class DocumentController {

    @Autowired
    private DocumentServiceimpl documentService;

    @PostMapping(value = "/upload_document",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document",
            description = "Parses the uploaded file, chunks it and indexes the chunks into the pgvector store")
    public ResponseEntity<DocumentDto> uploadDocument(@RequestParam("file") MultipartFile file) {
        DocumentDto documentDto = documentService.uploadDocument(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(documentDto);
    }

}
