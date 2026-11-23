package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.Entity.*;
import com.lcdw.springai.docmind01.Execption.Document_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionServiceTest {
    private final IngestionService ingestion = new IngestionService();
    private final VectorStore vectors = mock(VectorStore.class);
    private final DocumnetMetadatarepo repo = mock(DocumnetMetadatarepo.class);
    private final DocumentMetadata metadata = DocumentMetadata.builder().id("d1").ownerId("alice")
            .filename("policy.txt").contentType("text/plain").build();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(ingestion, "documnetMetadatarepo", repo);
        ReflectionTestUtils.setField(ingestion, "vectorStore", vectors);
        ReflectionTestUtils.setField(ingestion, "appproperties", new Appproperties());
        ReflectionTestUtils.setField(ingestion, "chunkRepository", mock(com.lcdw.springai.docmind01.Repo.ScopedChunkRepository.class));
    }

    @Test void parsedMetadataCannotOverrideOwnershipOrDocumentIdentity() {
        int count = ingestion.ingest(metadata, List.of(new Document("Customers may request refunds within thirty days of purchase.",
                Map.of("ownerId", "bob", "documentId", "foreign"))));
        assertThat(count).isEqualTo(1);
        verify(vectors).add(argThat(chunks -> chunks.stream().allMatch(chunk -> "alice".equals(chunk.getMetadata().get("ownerId"))
                && "d1".equals(chunk.getMetadata().get("documentId")))));
        assertThat(metadata.getStatus()).isEqualTo(Document_Status.indexed);
    }

    @Test void emptyExtractionFailsWithoutIndexing() {
        assertThatThrownBy(() -> ingestion.ingest(metadata, List.of())).isInstanceOf(Document_EXE.class).hasMessageContaining("OCR");
        assertThat(metadata.getStatus()).isEqualTo(Document_Status.failed);
        verifyNoInteractions(vectors);
    }

    @Test void embeddingFailureIsNeverMarkedIndexed() {
        doThrow(new IllegalStateException("private provider internals")).when(vectors).add(anyList());
        assertThatThrownBy(() -> ingestion.ingest(metadata, List.of(new Document("Customers may request refunds within thirty days of purchase."))))
                .isInstanceOf(Document_EXE.class).hasMessageNotContaining("private provider internals");
        assertThat(metadata.getStatus()).isEqualTo(Document_Status.failed);
        assertThat(metadata.getErrorMessage()).doesNotContain("private provider internals");
    }
}
