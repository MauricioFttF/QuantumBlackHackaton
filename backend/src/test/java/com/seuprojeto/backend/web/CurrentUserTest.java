package com.seuprojeto.backend.web;

import com.seuprojeto.backend.model.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserTest {

    private static final AuthenticatedUser SIGNED_IN = new AuthenticatedUser(42L, "pedro@usp.br");

    private final CurrentUser currentUser = new CurrentUser();

    @Test
    void current_authenticatedRequest_returnsTheIdentityTheFilterResolved() {
        assertThat(currentUser.current(requestWithAttribute(SIGNED_IN))).contains(SIGNED_IN);
    }

    @Test
    void current_noAttribute_isEmpty() {
        assertThat(currentUser.current(requestWithAttribute(null))).isEmpty();
    }

    @Test
    void current_attributeOfAnotherType_isEmptyRatherThanClassCastException() {
        assertThat(currentUser.current(requestWithAttribute("not-a-user"))).isEmpty();
    }

    @Test
    void conversationKey_isTheAccountId_soHistoryFollowsTheAccountNotTheBrowser() {
        assertThat(currentUser.conversationKey(requestWithAttribute(SIGNED_IN))).isEqualTo("42");
    }

    @Test
    void conversationKey_unauthenticated_isNullWhichMeansNoMemory() {
        assertThat(currentUser.conversationKey(requestWithAttribute(null))).isNull();
    }

    @Test
    void require_unauthenticated_throwsBecauseThatIsAWiringBugNotAClientError() {
        // Reaching a controller without an identity means the path is missing from
        // AuthenticationFilter.PROTECTED_PATHS — a 500 is the honest answer, not a 401.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> currentUser.require(requestWithAttribute(null)))
                .withMessageContaining("PROTECTED_PATHS");
    }

    private static HttpServletRequest requestWithAttribute(Object value) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthenticationFilter.AUTHENTICATED_USER)).thenReturn(value);
        when(request.getRequestURI()).thenReturn("/api/chat");
        return request;
    }
}
