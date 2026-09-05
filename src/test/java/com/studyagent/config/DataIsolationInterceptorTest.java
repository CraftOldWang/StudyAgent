package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.identity.IdentityResolver;
import com.studyagent.identity.IdentityScope;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.context.web.WebAppConfiguration;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        DataIsolationConfiguration.class,
        CurrentUserContext.class,
        IdentityResolver.class,
        IdentityScope.class
})
class DataIsolationInterceptorTest {

    @Autowired
    private MybatisPlusInterceptor mybatisPlusInterceptor;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private IdentityResolver identityResolver;

    @Autowired
    private IdentityScope identityScope;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldInjectUserIdIntoPlainSelect() throws SQLException {
        String rewritten = rewrite("SELECT id FROM documents", "42");

        assertThat(normalize(rewritten)).contains("where user_id = 42");
    }

    @Test
    void shouldPreserveExistingWhereAndAppendUserId() throws SQLException {
        String rewritten = rewrite("SELECT id FROM documents WHERE status = 'READY'", "42");

        assertThat(normalize(rewritten))
                .contains("where status = 'ready'")
                .contains("and user_id = 42");
    }

    @Test
    void shouldInjectUserIdForBothJoinAliases() throws SQLException {
        String rewritten = rewrite("""
                SELECT d.id
                FROM documents d
                LEFT JOIN file_records f ON f.id = d.file_record_id
                """, "42");

        assertThat(normalize(rewritten))
                .contains("on f.id = d.file_record_id and f.user_id = 42")
                .contains("where d.user_id = 42");
    }

    @Test
    void shouldLeaveUsersTableUnchanged() throws SQLException {
        String rewritten = rewrite("SELECT id FROM users", "42");

        assertThat(normalize(rewritten)).isEqualTo("select id from users");
    }

    @Test
    void shouldFailClosedWhenHeaderIsMissing() {
        Throwable thrown = catchThrowable(() -> rewrite("SELECT id FROM documents", null));

        assertThat(rootCause(thrown)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void shouldFailClosedWhenHeaderIsInvalid() {
        Throwable thrown = catchThrowable(() -> rewrite("SELECT id FROM documents", "not-a-number"));

        assertThat(rootCause(thrown)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void shouldNotLeakUserIdBetweenSequentialRequests() throws SQLException {
        String firstRequest = rewrite("SELECT id FROM documents", "42");
        String secondRequest = rewrite("SELECT id FROM documents", "7");

        assertThat(normalize(firstRequest))
                .contains("user_id = 42")
                .doesNotContain("user_id = 7");
        assertThat(normalize(secondRequest))
                .contains("user_id = 7")
                .doesNotContain("user_id = 42");
    }

    private String rewrite(String sql, String userIdHeader) throws SQLException {
        MockHttpServletRequest request = new MockHttpServletRequest(applicationContext.getServletContext());
        if (userIdHeader != null) {
            request.addHeader("X-User-Id", userIdHeader);
        }
        ServletRequestAttributes requestAttributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(requestAttributes);

        try {
            Configuration configuration = new Configuration();
            BoundSql boundSql = new BoundSql(configuration, sql, List.of(), null);
            MappedStatement mappedStatement = new MappedStatement.Builder(
                    configuration,
                    "DataIsolationInterceptorTest.select",
                    parameterObject -> boundSql,
                    SqlCommandType.SELECT)
                    .build();

            try (IdentityScope.Binding ignored = identityScope.bind(identityResolver.resolve(request))) {
                tenantLineInterceptor().beforeQuery(
                        null,
                        mappedStatement,
                        null,
                        RowBounds.DEFAULT,
                        null,
                        boundSql);
            }
            return boundSql.getSql();
        } finally {
            requestAttributes.requestCompleted();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private TenantLineInnerInterceptor tenantLineInterceptor() {
        assertThat(mybatisPlusInterceptor.getInterceptors()).hasSize(1);
        return (TenantLineInnerInterceptor) mybatisPlusInterceptor.getInterceptors().get(0);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static Throwable rootCause(Throwable throwable) {
        assertThat(throwable).isNotNull();
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
