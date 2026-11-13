package io.github.illuseahashmap.workflow.process.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.ProcessDeploymentService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentResult;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessDeploymentControllerTest {

    private final ProcessDeploymentService service = mock(ProcessDeploymentService.class);
    private final ProcessDeploymentController controller = new ProcessDeploymentController(service);
    private final ProcessDeploymentResult result =
            new ProcessDeploymentResult("d1", "p:1:x", "p", "P", 1, false, null, null, null);

    @BeforeEach
    void serviceClient() {
        CurrentPrincipalContext.set(new CurrentPrincipal("SERVICE", "flow-admin-be", "flow-admin-be",
                "flow-admin-be", "t1", Set.of(), Set.of()));
    }

    @AfterEach
    void clear() {
        CurrentPrincipalContext.clear();
    }

    @Test
    void theActingUserBecomesTheAuditNameButTheCallerStaysTheServiceClient() {
        ProcessDeploymentRequest request = new ProcessDeploymentRequest("pub", null, "p", "P", "<xml/>",
                List.of(), List.of(), null, null);
        when(service.deploy(request)).thenReturn(result);

        assertThat(controller.deploy("  alice  ", request).data()).isEqualTo(result);

        CurrentPrincipal principal = CurrentPrincipalContext.current();
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.principalId()).isEqualTo("flow-admin-be");
        assertThat(principal.principalType()).isEqualTo("SERVICE");
    }

    @Test
    void withoutTheHeaderThePrincipalIsUnchanged() {
        when(service.suspend("p")).thenReturn(result);
        controller.suspend(null, "p");
        assertThat(CurrentPrincipalContext.current().username()).isEqualTo("flow-admin-be");
        ActingUser.apply("x".repeat(300));
        assertThat(CurrentPrincipalContext.current().username()).hasSize(255);
    }

    @Test
    void delegatesTheOtherOperations() {
        when(service.latest("p")).thenReturn(Optional.of(result));
        when(service.latest("missing")).thenReturn(Optional.empty());
        when(service.resume("p")).thenReturn(result);

        assertThat(controller.latest("p").data()).isEqualTo(result);
        assertThatThrownBy(() -> controller.latest("missing")).hasMessageContaining("not deployed");
        assertThat(controller.resume("bob", "p").data()).isEqualTo(result);
        controller.deleteVersion("bob", "p", 2);
        verify(service).deleteVersion("p", 2);
    }
}
