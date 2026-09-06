package com.yilan.memory.adapter.rest;

import com.yilan.memory.application.management.*;
import com.yilan.memory.application.privacy.*;
import com.yilan.memory.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ForgetControllerTest {
    @Test void frozenDeleteRequiresFreshLearnerAndReturnsExistingReceiptShape() throws Exception {
        var id = UUID.randomUUID();
        var repository = new ForgetRepository() {
            public DeletionReceipt requestFull(String s,String k,java.time.Instant at){ return new DeletionReceipt(id,"BLOCKED",at); }
            public DeletionReceipt requestSingle(String s,UUID a,String k,java.time.Instant at){ return new DeletionReceipt(id,"BLOCKED",at); }
            public boolean completeFull(UUID id,java.time.Instant at){return true;} public DeletionVerifier.Verification verify(UUID id){return new DeletionVerifier.Verification(id,DeletionVerifier.Scope.FULL,0,0,true,true);}
        };
        var forget = new ForgetService(repository, s -> {}, Clock.systemUTC());
        var management = new MemoryManagementController(new ListMemoriesService(new EmptyRepository()), new GetMemoryExplanationService(new EmptyRepository()), new ConfirmMemoryService(new EmptyRepository()), new CorrectMemoryService(new EmptyRepository()), new DisableMemoryService(new EmptyRepository()), forget);
        var mvc = MockMvcBuilders.standaloneSetup(management).setControllerAdvice(new RestProblemHandler()).build();
        mvc.perform(delete("/v1/me/memories").header("Idempotency-Key", "i".repeat(16)).requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, new AuthenticatedSubject("s".repeat(43), Set.of(MemoryRole.LEARNER), true)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("BLOCKED")).andExpect(jsonPath("$.request_id").exists()).andExpect(jsonPath("$.requested_at").exists());
    }

    @Test void frozenSingleDeleteKeepsFreshAuthenticationAheadOfTheNotFoundMapping() throws Exception {
        var id = UUID.randomUUID();
        var repository = new ForgetRepository() {
            public DeletionReceipt requestFull(String s,String k,java.time.Instant at){ throw new UnsupportedOperationException(); }
            public DeletionReceipt requestSingle(String s,UUID a,String k,java.time.Instant at){ throw new MemoryManagementRepository.MemoryNotFoundException(); }
            public DeletionVerifier.Verification verify(UUID id){ throw new UnsupportedOperationException(); }
        };
        var forget = new ForgetService(repository, s -> {}, Clock.systemUTC());
        var management = new MemoryManagementController(new ListMemoriesService(new EmptyRepository()), new GetMemoryExplanationService(new EmptyRepository()), new ConfirmMemoryService(new EmptyRepository()), new CorrectMemoryService(new EmptyRepository()), new DisableMemoryService(new EmptyRepository()), forget);
        var mvc = MockMvcBuilders.standaloneSetup(management).setControllerAdvice(new RestProblemHandler()).build();

        mvc.perform(delete("/v1/me/memories/{id}", id).header("Idempotency-Key", "i".repeat(16)).requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, new AuthenticatedSubject("s".repeat(43), Set.of(MemoryRole.LEARNER), false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FRESH_AUTH_REQUIRED"));
        mvc.perform(delete("/v1/me/memories/{id}", id).header("Idempotency-Key", "i".repeat(16)).requestAttr(SubjectBindingFilter.AUTHENTICATED_SUBJECT_ATTRIBUTE, new AuthenticatedSubject("s".repeat(43), Set.of(MemoryRole.LEARNER), true)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMORY_NOT_FOUND"));
    }
    private static final class EmptyRepository implements MemoryManagementRepository {
      public MemoryPage list(ListQuery q){return new MemoryPage(List.of(),null);} public Optional<MemoryDetail> findDetail(String s,UUID i){return Optional.empty();}
      public ActionResult confirm(Mutation m){throw new UnsupportedOperationException();} public ActionResult correct(Correction m){throw new UnsupportedOperationException();} public ActionResult disable(Mutation m){throw new UnsupportedOperationException();}
    }
}
