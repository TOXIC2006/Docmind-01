package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Execption.Document_EXE;
import io.micrometer.common.lang.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class ParseService {
    public List<Document> parse(MultipartFile file) {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "document";

        String contentType = file.getContentType() != null
                ? file.getContentType()
                : "application/octet-stream";


         try {
             Resource resource = new ByteArrayResource(file.getBytes()) {
                 @Override
                 public @Nullable String getFilename() {
                     return fileName;
                 }
             };

             if (fileName.toLowerCase().endsWith(".pdf") || contentType.contains("pdf")) {
                 return parsePdf(resource);
             } else {
                 return parseGenericFile(resource);
             }

         } catch (Exception e) {
             throw new Document_EXE("failed to parse file: " + fileName, e);
         }
    }
    private List<Document> parseGenericFile(Resource resource) {
        return  new TikaDocumentReader(resource).read();
    }

    private List<Document> parsePdf(Resource resource) {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource,
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0)
                                .build())
                        .withPagesPerDocument(1)
                        .build());

        return pdfReader.read();
    }
}
