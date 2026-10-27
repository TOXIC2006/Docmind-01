package com.lcdw.springai.docmind01.DTO;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearcRequestDTO {
    @NotEmpty(message = "Query must not be empty")
    private String query;
    private  String documentId;
    private  Integer topK;
    private  Double  similarityThreshold;

}
