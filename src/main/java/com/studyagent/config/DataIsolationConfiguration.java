package com.studyagent.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.studyagent.identity.IdentityScope;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataIsolationConfiguration {

    private static final String USER_ID_COLUMN = "user_id";
    private static final java.util.Set<String> TABLES_WITHOUT_USER_ID = java.util.Set.of("users", "document_chunks");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(IdentityScope identityScope) {
        TenantLineHandler dataIsolationHandler = new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(identityScope.requireUserId());
            }

            @Override
            public String getTenantIdColumn() {
                return USER_ID_COLUMN;
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TABLES_WITHOUT_USER_ID.stream().anyMatch(table -> table.equalsIgnoreCase(tableName));
            }
        };

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(dataIsolationHandler));
        return interceptor;
    }
}
