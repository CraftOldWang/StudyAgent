# D-Q15：异常体系

> 状态：READY_FOR_DECISION

## 目标

解除 `algo` 对 Web/业务异常的依赖，同时保持“明确的外部输入错误 → HTTP 400 + 稳定业务码”，并避免把程序 bug 伪装成 400。

## 方案

### A. `algo` 抛 IllegalArgumentException，全局映射为 400

优点：改动最少，`algo` 没有项目依赖。

缺点：`IllegalArgumentException` 也常用于表示内部调用者违反前置条件。全局映射会把编程错误、错误配置等误报成用户输入错误。

判断：只适合临时补丁，不适合作为最终异常分类。

### B. `algo` 抛标准异常，服务层捕获后转 BusinessException

优点：保持算法纯净，现有 Web 契约容易复用。

缺点：转换散落在 service；如果捕获范围过大，会掩盖真实缺陷。

判断：可接受的次选；必须只捕获明确异常，并保留 cause。

### C. `algo` 定义纯 Java 语义异常，Web Adapter 统一映射

例如 `InvalidReviewRatingException` 或稳定的 `DomainRuleViolation`，不依赖 Spring/Web；`RestControllerAdvice` 把已分类的异常映射到 ProblemDetail/项目错误响应。

优点：语义清晰、可纯单测、依赖方向正确；未知 `IllegalArgumentException` 仍可保持 500。

缺点：需要设计异常分类、错误码与映射表，类型不能无限膨胀。

## 项目适配与推荐

推荐 C：

- `algo` 只抛纯 Java、语义明确的规则异常；
- Web 层只把已登记的外部输入/领域规则异常映射为 400；
- 未分类 `IllegalArgumentException`、`IllegalStateException` 和未知 RuntimeException 保持 500；
- 响应包含稳定 code、用户可读 message、traceId/instance，不返回堆栈和 provider 原文。

如果项目不希望为每条规则建立异常类，可以使用一个小型 `DomainRuleViolation(code, message)`，但 `code` 必须是受控枚举/值对象。

## 测试边界

- 非法业务输入 → 400 + 稳定 code；
- 模拟内部 IllegalArgumentException → 不被误映射为 400；
- 未知 RuntimeException → 500 且不泄露堆栈；
- service 转换保留 cause；
- `algo` 测试不加载 Spring；
- Web 错误响应做 MockMvc/契约测试。

## 需要用户拍板

- A/B/C；
- 细粒度异常类，还是统一 `DomainRuleViolation`；
- 是否采用 RFC 9457 `ProblemDetail` 作为 HTTP 外壳；
- 是否保留现有 `BusinessException` 作为 application/web exception。

## 一手来源

- [Spring MVC Exceptions](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/exceptionhandlers.html)
- [Spring REST Error Responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Boot Servlet Error Handling](https://docs.spring.io/spring-boot/reference/web/servlet.html)
