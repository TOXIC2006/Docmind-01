package com.lcdw.springai.docmind01.DTO;

import com.lcdw.springai.docmind01.Entity.Document_Status;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DocumentDto {
    private String id;
    private String fileName;
    private Long fileSize;
    private Document_Status status;
    private Integer chunksCreated;
    private String message;
}
