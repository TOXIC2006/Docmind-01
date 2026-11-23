package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Entity.*;
import com.lcdw.springai.docmind01.Execption.ResourceNotFound_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import com.lcdw.springai.docmind01.Service.Security.AppUserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIsolationTest {
    private final DocumnetMetadatarepo repo = mock(DocumnetMetadatarepo.class);
    private final VectorStore vectors = mock(VectorStore.class);
    private final ParseService parser = mock(ParseService.class);
    private final IngestionService ingestion = mock(IngestionService.class);
    private final DocumentServiceimpl service = new DocumentServiceimpl();
    private final com.lcdw.springai.docmind01.Repo.ScopedChunkRepository chunks = mock(com.lcdw.springai.docmind01.Repo.ScopedChunkRepository.class);
    private final DocumentSemanticCache cache = mock(DocumentSemanticCache.class);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "documnetMetadatarepo", repo);
        ReflectionTestUtils.setField(service, "vectorStore", vectors);
        ReflectionTestUtils.setField(service, "parseService", parser);
        ReflectionTestUtils.setField(service, "ingestionService", ingestion);
        ReflectionTestUtils.setField(service, "chunkRepository", chunks);
        ReflectionTestUtils.setField(service, "semanticCache", cache);
        var principal = new AppUserPrincipal(User.builder().id("alice").role("USER").build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void listingIsOwnerScoped() {
        when(repo.findByOwnerIdOrderByCreatedAtDesc("alice")).thenReturn(List.of());
        assertThat(service.listDocuments()).isEmpty();
        verify(repo, never()).findAll();
    }

    @Test void foreignReadAndDeleteReturn404WithoutTouchingVectors() {
        when(repo.findByIdAndOwnerId("foreign", "alice")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDocument("foreign")).isInstanceOf(ResourceNotFound_EXE.class);
        assertThatThrownBy(() -> service.deleteDocument("foreign")).isInstanceOf(ResourceNotFound_EXE.class);
        verifyNoInteractions(vectors);
        verify(repo, never()).delete(any());
    }

    @Test void uploadGetsOwnerFromPrincipalNotFileMetadata() {
        when(parser.parse(any())).thenReturn(List.of());
        service.uploadDocument(new MockMultipartFile("file", "manual.txt", "text/plain", "refunds".getBytes()));
        verify(repo).save(argThat(d -> "alice".equals(d.getOwnerId())));
    }

    @Test void deletionUsesAllOwnedIdsAndInvalidatesCacheOnlyAfterSuccess() {
        var metadata = DocumentMetadata.builder().id("d1").ownerId("alice").build();
        when(repo.findByIdAndOwnerId("d1", "alice")).thenReturn(Optional.of(metadata));
        var ids = java.util.stream.IntStream.range(0, 1201).mapToObj(i -> "chunk-" + i).toList();
        when(chunks.ids("alice", "d1")).thenReturn(ids);
        service.deleteDocument("d1");
        var order = inOrder(vectors, chunks, repo, cache);
        order.verify(vectors).delete(ids.subList(0, 1000));
        order.verify(vectors).delete(ids.subList(1000, 1201));
        order.verify(chunks).delete("alice", "d1");
        order.verify(repo).delete(metadata);
        order.verify(cache).evictDocument("alice", "d1");
    }

    @Test void failedVectorDeleteRetainsMetadataAndLexicalCorpus() {
        when(repo.findByIdAndOwnerId("d1", "alice")).thenReturn(Optional.of(DocumentMetadata.builder().id("d1").ownerId("alice").build()));
        when(chunks.ids("alice", "d1")).thenReturn(List.of("c1"));
        doThrow(new IllegalStateException("offline")).when(vectors).delete(List.of("c1"));
        assertThatThrownBy(() -> service.deleteDocument("d1")).isInstanceOf(IllegalStateException.class);
        verify(chunks, never()).delete(anyString(), anyString());
        verify(repo, never()).delete(any());
        verifyNoInteractions(cache);
    }

    @Test void invalidFilesAreRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.uploadDocument(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadDocument(new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadDocument(new MockMultipartFile("file", "../file.txt", "text/plain", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo, vectors, parser, ingestion);
    }
}
