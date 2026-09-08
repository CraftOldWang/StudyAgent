package com.studyagent.agent.web;

import com.studyagent.agent.integration.ModelCallScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ModelCallScopeFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        ModelCallScope scope = new ModelCallScope(UUID.randomUUID().toString(),
                request.getMethod() + " " + request.getRequestURI());
        response.setHeader("X-Trace-Id", scope.traceId());
        ModelCallScope.bind(scope);
        try {
            filterChain.doFilter(request, response);
        } finally {
            ModelCallScope.clear();
        }
    }
}
