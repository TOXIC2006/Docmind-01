package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.Entity.*;
import com.lcdw.springai.docmind01.Repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegacyVectorMigrationTest {
    private final DocumnetMetadatarepo documents = mock(DocumnetMetadatarepo.class);
    private final ScopedChunkRepository chunks = mock(ScopedChunkRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final VectorStore vectors = mock(VectorStore.class);
    private final LegacyVectorMigration migration = new LegacyVectorMigration(documents, chunks, jdbc, vectors,
            new ObjectMapper(), new Appproperties(), mock(ConfigurableApplicationContext.class));

    @Test void refusesOwnerlessDocumentsWithoutNetworkOrWrites() {
        when(documents.findById("old")).thenReturn(Optional.of(DocumentMetadata.builder().id("old").status(Document_Status.indexed).build()));
        assertThatThrownBy(() -> migration.migrate("old")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(chunks, jdbc, vectors);
    }

    @Test void alreadyMigratedDocumentIsIdempotentlySkipped() {
        when(documents.findById("d1")).thenReturn(Optional.of(DocumentMetadata.builder().id("d1").ownerId("alice")
                .status(Document_Status.indexed).vectorRevision("r1").build()));
        migration.migrate("d1");
        verifyNoInteractions(chunks, jdbc, vectors);
    }

    @Test void failedUpsertNeverAdvancesRevisionAndNeverDeletesLegacyRows() {
        var document = DocumentMetadata.builder().id("d1").ownerId("alice").totalChunks(1).status(Document_Status.indexed).build();
        when(documents.findById("d1")).thenReturn(Optional.of(document));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(new Document("Refunds take thirty days.")));
        doThrow(new IllegalStateException("outage")).when(vectors).add(anyList());
        assertThatThrownBy(() -> migration.migrate("d1")).isInstanceOf(IllegalStateException.class);
        assertThat(document.getVectorRevision()).isNull();
        verify(documents, never()).save(any());
        verify(jdbc, never()).update(anyString());
    }
}
