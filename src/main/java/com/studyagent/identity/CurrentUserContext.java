package com.studyagent.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class CurrentUserContext {

    private final Long userId;

    public CurrentUserContext(IdentityResolver identityResolver, HttpServletRequest request) {
        this.userId = identityResolver.resolve(request);
    }

    public Long userId() {
        return userId;
    }
}
