package com.lcdw.springai.docmind01.Controller;

import com.lcdw.springai.docmind01.DTO.ChatRequestDTO;
import com.lcdw.springai.docmind01.DTO.ChatResponseDTO;
import com.lcdw.springai.docmind01.DTO.SearcRequestDTO;
import com.lcdw.springai.docmind01.DTO.SearchanswerDTO;
import com.lcdw.springai.docmind01.Execption.API_Respone;
import com.lcdw.springai.docmind01.Service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

//import static jdk.vm.ci.hotspot.HotSpotCompilationRequestResult.success;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "DocMind RAG API", description = "Retrieval and question answering over indexed documents")
public class RagController {

    private final RagService ragService;

    @PostMapping("/search")
    @Operation(summary = "Search the vector store",
            description = "Returns the matching chunks and their citations. Does not call the LLM.")
    public ResponseEntity<Map<String, Object>> search(@Valid @RequestBody SearcRequestDTO request) {
        SearchanswerDTO response = ragService.searchanswer(request);
        Map<String, Object> map = Map.of(
                "success", true,
                "message", "Search successful",
                "timestamp", java.time.LocalDateTime.now(),
                "data", response
        );
        return ResponseEntity.status(200).body(map);

    }

    @PostMapping("/chat")
    @Operation(summary = "Ask a question about your documents",
            description = "Retrieves the matching chunks, then has the LLM answer using them as context.")
    public ResponseEntity<ChatResponseDTO> chat(@Valid @RequestBody ChatRequestDTO request) {
        ChatResponseDTO response = ragService.chat(request);// later

        return ResponseEntity.ok(ragService.chat(request));
    }
    @PostMapping("/fluxchat")
    @Operation(summary = "Ask  an get the answer in flux chat",
            description = "Retrieves the matching chunks, then has the LLM answer using them as context.")
    public ResponseEntity<Flux<String>> fluxchat(@Valid @RequestBody ChatRequestDTO request) {
        return ResponseEntity.ok(ragService.streamChat(request));
    }
}
