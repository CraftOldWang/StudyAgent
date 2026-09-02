package com.studyagent.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class IdentityResolver {

    static final String USER_ID_HEADER = "X-User-Id";

    public Long resolve(HttpServletRequest request) {
        return Long.valueOf(request.getHeader(USER_ID_HEADER));
    }
}
