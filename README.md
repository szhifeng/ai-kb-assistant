# Spring AI 个人知识库问答助手

基于 Spring AI 2.0 的个人知识库问答系统：本地文档批量入库、语义检索、SSE 流式多轮对话，并具备引用溯源、Function Calling、多模型路由等企业级能力。作为 Java AI 应用开发的 RAG / Agent 工程实践模板。

## 技术栈

- Spring Boot 4.1 / Java 25
- Spring AI 2.0.0-RC2（ChatClient + Advisors）
- PGVector（向量库）/ Redis Stack（会话记忆 + 限流）
- DeepSeek（主力 Chat）/ OpenAI（向量化 + Chat 路由备选）
- Vue3 + Vite（前端，独立子模块）

## 模块结构

```
ai-kb-assistant/
├── kb-assistant-server/    # Spring Boot 后端（API + RAG + SSE）
├── kb-assistant-web-ui/    # Vue3 前端
├── docker/                 # docker-compose（pgvector + redis-stack）
└── docs/                   # 设计文档与实现计划
```

## 快速开始

### 1. 准备密钥

复制 `.env.example` 为 `.env` 并填入真实 Key：

```
OPENAI_API_KEY=sk-...
DEEPSEEK_API_KEY=sk-...
TAVILY_API_KEY=tvly-...
```

向量化使用 OpenAI `text-embedding-3-small`（1536 维），Chat 默认 DeepSeek。

### 2. 启动依赖（PGVector + Redis Stack）

```bash
docker compose -f docker/docker-compose.yml up -d
```

> 注意：会话记忆 starter 依赖 RediSearch，必须使用 `redis/redis-stack` 镜像（非普通 redis）。

### 3. 启动后端

需先在当前 shell 设置环境变量（或通过 IDE 注入 `.env`），然后：

```bash
mvnd -pl kb-assistant-server spring-boot:run
```

应用启动后监听 `http://localhost:8080`，PGVector 表 `vector_store` 会自动创建。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | 上传文档（multipart `file`），返回入库分片数 |
| GET | `/api/chat/stream?question=...` | SSE 流式问答，token 流 + 末尾 `citations` 引用帧 |

### 冒烟示例

```bash
# 上传一篇文档
curl -F "file=@./sample.md" http://localhost:8080/api/documents

# 流式提问（-N 关闭缓冲以观察 SSE）
curl -N "http://localhost:8080/api/chat/stream?question=这篇文档讲了什么"
```

预期：上传返回 `chunkCount > 0`；问答持续输出 `event:token` 帧，结束追加 `event:citations` 帧（含来源 source）。

## 构建与测试

```bash
mvnd -pl kb-assistant-server test
```

单元测试用 mock，无需真实 Key；集成测试 `IngestIntegrationTest` 针对本地 docker-compose 的 pgvector（需先 `docker compose up -d`）。

## 里程碑

- M1 工程骨架 / M2 入库管线 / M3 流式 RAG 问答（MVP）
- M4 多轮记忆 + Token 限流 / M5 Function Calling + 多模型路由 / M6 Vue3 前端
