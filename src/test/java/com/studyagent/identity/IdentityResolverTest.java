package com.studyagent.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IdentityResolverTest {

    private final IdentityResolver identityResolver = new IdentityResolver();

    @Test
    void shouldResolveUserIdFromRequestHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");

        Long userId = identityResolver.resolve(request);

        assertThat(userId).isEqualTo(42L);
    }
}
