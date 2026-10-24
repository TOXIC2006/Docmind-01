package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.DTO.DocumentDto;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    DocumentDto uploadDocument(MultipartFile documentPath);
}
