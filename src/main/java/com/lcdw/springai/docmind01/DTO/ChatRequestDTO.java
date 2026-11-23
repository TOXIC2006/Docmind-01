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
public class ChatRequestDTO {
    @jakarta.validation.constraints.NotBlank(message = "Query must not be blank")
    @jakarta.validation.constraints.Size(max = 2000, message = "Query must be at most 2000 characters")
    private String query;
    private  String documentId;
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(20)
    private Integer topK;
    @jakarta.validation.constraints.DecimalMin("0.0")
    @jakarta.validation.constraints.DecimalMax("1.0")
    private Double similarityThreshold;
    private Boolean rewriteQuery;
    private String retrievalMode;

}
