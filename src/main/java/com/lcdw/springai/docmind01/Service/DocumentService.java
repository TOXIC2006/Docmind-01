package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.DTO.DocumentDto;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {
    DocumentDto uploadDocument(MultipartFile documentPath);

    List<DocumentDto> uploadDocuments(List<MultipartFile> files);

    List<DocumentDto> listDocuments();

    DocumentDto getDocument(String id);

    void deleteDocument(String id);
}
