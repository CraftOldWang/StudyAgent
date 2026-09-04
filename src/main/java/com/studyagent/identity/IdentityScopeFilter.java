package com.studyagent.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class IdentityScopeFilter extends OncePerRequestFilter {

    private final IdentityResolver identityResolver;
    private final IdentityScope identityScope;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try (IdentityScope.Binding ignored = identityScope.bind(identityResolver.resolve(request))) {
            filterChain.doFilter(request, response);
        }
    }
}
