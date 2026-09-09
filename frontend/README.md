# StudyPilot 前端

React + TypeScript + Vite，面向桌面浏览器演示。包括知识库、分片上传、检索与来源、重点计划、自然语言学习/SSE、测验、复习卡及 Anki 导出。完整后端、Docker 与 ASR 启动方式见 [项目 README](../README.md)。

## 环境要求

- Node.js（使用满足当前锁文件依赖要求的版本）
- StudyAgent 后端运行在 `http://localhost:8080`

## 本地启动

```powershell
npm ci
npm run dev
```

已安装依赖且锁文件未变化时跳过 `npm ci`。开发服务器监听 `5173`，并把 `/api` 代理到 `http://localhost:8080`。演示 API 使用默认用户 `X-User-Id: 1`。

## 使用流程

1. 创建或选择知识库，可在侧边栏重命名。
2. 上传课件或音视频，页面自动刷新处理状态；新音视频需要启动 ASR worker。
3. 文档进入 `INDEXED` 后，选择普通检索或 Agent 检索并提问。
4. 结果区展示回答、命中片段及文档出处。
5. 选择课件和可选习题，输入学习目标生成重点计划；也可输入会话 ID 恢复。
6. 通过自然消息完成知识点讲解/答疑、测验、反馈和复习卡片，可将卡片导出到 Anki。

## 验证

```powershell
npm run build
```

构建包含 TypeScript 检查，不再单独重复 `typecheck`。改动交互逻辑时运行相关现有测试，例如 `npm test -- src/stream.test.ts`，不要求每次全量执行。页面只做桌面验收。

页面会轮询处理中的文档，在 `INDEXED` 或 `FAILED` 时停止；终态集中在 `src/status.ts`。数据库 ID 按字符串传输，避免超出 JavaScript 安全整数范围。

学习消息通过 SSE 流式展示，历史与产物从后端恢复。当前没有独立的 trace 管理页面。
