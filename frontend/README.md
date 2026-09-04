# StudyAgent Frontend

React 18 + TypeScript + Vite 的最小 M1 页面，覆盖知识库管理、PDF 上传状态、普通检索和 Agent 检索。

## 环境要求

- Node.js 18+
- StudyAgent 后端运行在 `http://localhost:8080`

## 本地启动

```powershell
npm install
npm run dev
```

开发服务器监听 `5173`，并把 `/api` 代理到 `http://localhost:8080`。所有 API 请求由统一客户端添加 `X-User-Id: 1`，对应首个里程碑的默认用户身份。

## 使用流程

1. 创建或选择知识库，可在侧边栏重命名。
2. 上传 PDF，页面会自动刷新处理状态。
3. 文档进入 `INDEXED` 后，选择普通检索或 Agent 检索并提问。
4. 结果区展示回答、命中片段及文档出处。

## 验证

```powershell
npm test
npm run typecheck
npm run build
```

当前仅允许上传 PDF。页面会轮询仍在处理中的文档，并在 `INDEXED` 或 `FAILED` 时停止；终态定义集中在 `src/status.ts`。

M1 刻意保持单页、普通 HTTP 请求和轻量自定义样式，不引入 SSE、路由框架或组件库。
