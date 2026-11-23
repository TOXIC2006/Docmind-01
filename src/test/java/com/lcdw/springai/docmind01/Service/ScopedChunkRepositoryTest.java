package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Repo.ScopedChunkRepository;
import com.lcdw.springai.docmind01.Execption.Document_EXE;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScopedChunkRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ScopedChunkRepository repo = new ScopedChunkRepository(jdbc, new ObjectMapper());

    @Test void sqlUsesOwnedTextAndCurrentRevisionNotTheVectorTable() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        repo.load("alice", "d1", 100);
        verify(jdbc).query(contains("d.vector_revision = c.vector_revision"), any(RowMapper.class), eq("alice"), eq("d1"), eq(101));
        verify(jdbc).query(contains("FROM docmind_chunks"), any(RowMapper.class), eq("alice"), eq("d1"), eq(101));
    }

    @Test void corpusOverflowFailsInsteadOfSilentlyTruncating() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new Document("first"), new Document("second")));
        assertThatThrownBy(() -> repo.load("alice", null, 1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corpus limit");
    }

    @Test void legacyDocumentsRequireExplicitMigration() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        assertThatThrownBy(() -> repo.load("alice", "old", 10)).isInstanceOf(Document_EXE.class).hasMessageContaining("migration");
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test void foreignChunksCannotReplaceOwnedCorpus() {
        var foreign = new Document("text", Map.of("ownerId", "bob", "documentId", "d1", "vectorRevision", "r1"));
        assertThatThrownBy(() -> repo.replace("alice", "d1", "r1", List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void deletionAndIdsAreOwnerScoped() {
        repo.ids("alice", "d1");
        repo.delete("alice", "d1");
        verify(jdbc).queryForList(contains("owner_id = ? AND document_id = ?"), eq(String.class), eq("alice"), eq("d1"));
        verify(jdbc).update(contains("owner_id = ? AND document_id = ?"), eq("alice"), eq("d1"));
    }
}
