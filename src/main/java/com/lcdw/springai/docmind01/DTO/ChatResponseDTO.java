package com.lcdw.springai.docmind01.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponseDTO {

    private  String  query;
    private  String  answer;
    private  int totalmatches;
    private  List<CitationDto> matches;
}
