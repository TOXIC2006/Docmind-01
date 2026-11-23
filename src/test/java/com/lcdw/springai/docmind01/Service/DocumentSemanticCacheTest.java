package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.DTO.*;
import com.lcdw.springai.docmind01.Entity.*;
import com.lcdw.springai.docmind01.Execption.ResourceNotFound_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import com.lcdw.springai.docmind01.Service.Security.AppUserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentSemanticCacheTest {
    private final DocumnetMetadatarepo documents = mock(DocumnetMetadatarepo.class);
    private final EmbeddingModel embeddings = mock(EmbeddingModel.class);
    private final Appproperties properties = new Appproperties();
    private final Clock clock = mock(Clock.class);
    private final DocumentSemanticCache cache = new DocumentSemanticCache(documents, embeddings, new ObjectMapper(), properties, clock);
    private final DocumentSemanticCache.Scope scope = new DocumentSemanticCache.Scope("alice", "d1", "r1", 5, .25, false, "hybrid");

    @BeforeEach void setup() {
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 0});
        when(clock.millis()).thenReturn(1000L);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private ChatResponseDTO answer() {
        return ChatResponseDTO.builder().answer("Thirty days. [S1]").grounded(true).abstained(false)
                .matches(List.of(CitationDto.builder().documentId("d1").citationId("S1").snippet("Thirty days.").build())).build();
    }
    private void seed(String query) { cache.store(cache.lookup(scope, query), answer()); }

    @Test void exactHitSkipsEvenTheEmbeddingAndReturnsDefensiveCopies() {
        seed("What is the refund window?");
        clearInvocations(embeddings);
        var first = cache.lookup(scope, "What is the refund window?");
        assertThat(first.diagnostics().status()).isEqualTo("EXACT_HIT");
        first.response().getMatches().get(0).setSnippet("tampered");
        assertThat(cache.lookup(scope, "What is the refund window?").response().getMatches().get(0).getSnippet()).isEqualTo("Thirty days.");
        verifyNoInteractions(embeddings);
    }

    @Test void paraphraseUsesCosineThreshold() {
        seed("What is the refund window?");
        assertThat(cache.lookup(scope, "How long do I have to request a refund?").diagnostics().status()).isEqualTo("SEMANTIC_HIT");
        when(embeddings.embed("Who owns Mars?")).thenReturn(new float[]{0, 1});
        assertThat(cache.lookup(scope, "Who owns Mars?").response()).isNull();
    }

    @Test void ownersDocumentsRevisionsAndRetrievalSettingsNeverShareAnswers() {
        seed("refund");
        for (var other : List.of(
                new DocumentSemanticCache.Scope("bob", "d1", "r1", 5, .25, false, "hybrid"),
                new DocumentSemanticCache.Scope("alice", "d2", "r1", 5, .25, false, "hybrid"),
                new DocumentSemanticCache.Scope("alice", "d1", "r2", 5, .25, false, "hybrid"),
                new DocumentSemanticCache.Scope("alice", "d1", "r1", 7, .25, false, "hybrid"),
                new DocumentSemanticCache.Scope("alice", "d1", "r1", 5, .8, false, "hybrid"),
                new DocumentSemanticCache.Scope("alice", "d1", "r1", 5, .25, true, "hybrid"),
                new DocumentSemanticCache.Scope("alice", "d1", "r1", 5, .25, false, "semantic"))) {
            assertThat(cache.lookup(other, "refund").response()).isNull();
        }
    }

    @Test void differentNumbersIdentifiersOrNegationCannotSemanticallyHit() {
        seed("Can I refund after 30 days for ERR_502A?");
        assertThat(cache.lookup(scope, "Can I refund after 60 days for ERR_502A?").response()).isNull();
        assertThat(cache.lookup(scope, "Can I refund after 30 days for ERR_502B?").response()).isNull();
        assertThat(cache.lookup(scope, "Can I not refund after 30 days for ERR_502A?").response()).isNull();
    }

    @Test void ttlAndExplicitEvictionExpireAnswers() {
        seed("refund");
        when(clock.millis()).thenReturn(901000L);
        assertThat(cache.lookup(scope, "refund").response()).isNull();
        seed("refund");
        cache.evictDocument("bob", "d1");
        assertThat(cache.lookup(scope, "refund").response()).isNotNull();
        cache.evictDocument("alice", "d1");
        assertThat(cache.lookup(scope, "refund").response()).isNull();
    }

    @Test void leastRecentlyUsedEntryIsEvictedAtCapacity() {
        properties.getCache().setMaxEntries(2);
        seed("refund 1"); seed("refund 2");
        cache.lookup(scope, "refund 1");
        seed("refund 3");
        assertThat(cache.lookup(scope, "refund 2").response()).isNull();
        assertThat(cache.lookup(scope, "refund 1").response()).isNotNull();
    }

    @Test void ungroundedAndForeignAnswersAreNeverCached() {
        var lookup = cache.lookup(scope, "refund");
        var unsupported = answer(); unsupported.setGrounded(false);
        cache.store(lookup, unsupported);
        assertThat(cache.lookup(scope, "refund").response()).isNull();
        var foreign = answer(); foreign.getMatches().get(0).setDocumentId("d2");
        cache.store(lookup, foreign);
        assertThat(cache.lookup(scope, "refund").response()).isNull();
    }

    @Test void allDocumentsBypassesCacheWithoutEmbedding() {
        assertThat(cache.lookup(null, "refund").diagnostics().status()).isEqualTo("BYPASS");
        verifyNoInteractions(embeddings);
    }

    @Test void embeddingFailureAndInvalidVectorsOnlyDisableCaching() {
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(cache.lookup(scope, "refund").diagnostics().status()).isEqualTo("UNAVAILABLE");
        reset(embeddings);
        when(embeddings.embed(anyString())).thenReturn(new float[]{Float.NaN, 1});
        assertThat(cache.lookup(scope, "refund").diagnostics().status()).isEqualTo("UNAVAILABLE");
    }

    @Test void ownershipAndStatusAreRecheckedFromDatabase() {
        var principal = new AppUserPrincipal(User.builder().id("alice").role("USER").build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var request = ChatRequestDTO.builder().documentId("d1").query("refund").build();
        when(documents.findByIdAndOwnerId("d1", "alice")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cache.scope(request)).isInstanceOf(ResourceNotFound_EXE.class);
        var doc = DocumentMetadata.builder().id("d1").ownerId("alice").status(Document_Status.failed).vectorRevision("r1").build();
        when(documents.findByIdAndOwnerId("d1", "alice")).thenReturn(Optional.of(doc));
        assertThat(cache.scope(request)).isNull();
        doc.setStatus(Document_Status.indexed);
        assertThat(cache.scope(request).revision()).isEqualTo("r1");
    }
}
