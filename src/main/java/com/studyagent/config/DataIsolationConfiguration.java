package com.studyagent.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.studyagent.identity.CurrentUserContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataIsolationConfiguration {

    private static final String USER_ID_COLUMN = "user_id";
    private static final String USERS_TABLE = "users";

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(CurrentUserContext currentUserContext) {
        TenantLineHandler dataIsolationHandler = new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(currentUserContext.userId());
            }

            @Override
            public String getTenantIdColumn() {
                return USER_ID_COLUMN;
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return USERS_TABLE.equalsIgnoreCase(tableName);
            }
        };

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(dataIsolationHandler));
        return interceptor;
    }
}
