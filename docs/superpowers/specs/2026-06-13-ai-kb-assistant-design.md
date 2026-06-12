# Spring AI 个人知识库问答助手 — 设计文档

- 日期：2026-06-13
- 状态：待评审
- 定位：开源练手项目（简历项目），突出 Java + Spring AI 的 RAG / Agent 工程能力

## 1. 目标与范围

构建一个基于 Spring AI 的个人知识库问答系统：本地文档批量入库 → 语义检索 → 多轮对话问答，并具备企业级可用特性（引用溯源、多轮记忆、Function Calling 工具调用、SSE 流式输出、多模型路由）。

项目按"能跑通的 MVP + 可增量的加分项"推进，避免一次性铺开导致烂尾。

### 1.1 MVP（主线，先跑通）

文档解析切片 → OpenAI 向量化 → 存 PGVector → ChatClient + RAG Advisor 检索问答 → **SSE 流式返回答案 + 引用列表**（流式为核心交互亮点，纳入 MVP）。

### 1.2 加分项（MVP 后增量）

Redis 多轮上下文记忆 + Token 限流控成本、Function Calling（联网搜索 / 本地 SQL 查询）、多模型路由切换（DeepSeek / OpenAI）、Vue3 前端。

### 1.3 明确不做（YAGNI）

- 用户体系 / 鉴权（练手项目，单用户假设；预留扩展点）。
- 通义千问接入：`spring-ai-alibaba` 当前主线构建于 Spring AI 1.0，与本项目 Boot 4 / AI 2.0-RC2 大概率不兼容，列为"后续扩展"，不在本期实现。
- Ollama / 本地模型：用户本地未部署，全程云端模型。
- 多向量库（MariaDB / Milvus）：只保留 PGVector。

## 2. 技术栈（最新线，体现新技术掌握）

| 维度 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 4.1.0 / Java 25 | 维持现有 pom |
| AI 框架 | Spring AI 2.0.0-RC2 | 全程一方 starter，踩坑最少 |
| Chat 模型 | DeepSeek（主力）+ OpenAI（路由对比） | 多模型路由叙事成立 |
| 向量化 | OpenAI `text-embedding-3-small`（1536 维） | DeepSeek 无 embedding 模型，由 OpenAI 承担 |
| 向量库 | PGVector（dimensions=1536, HNSW, COSINE） | 唯一向量库 |
| 记忆存储 | Redis（chat-memory-repository） | 多轮上下文持久化 |
| 文档解析 | Tika + Markdown document reader | PDF / Markdown / docx 等 |
| 联网搜索 | Tavily（Function Calling 工具） | 加分项 |
| 前端 | Vue3 + Vite | 独立子模块，后续做 |
| 部署 | Docker Compose（pgvector + redis） | 一键起依赖 |

### 2.1 依赖收敛

当前 pom 塞入 3 个向量库 + 5 类模型，需瘦身。

保留：`spring-ai-starter-vector-store-pgvector`、`spring-ai-starter-model-deepseek`、`spring-ai-starter-model-openai`、`spring-ai-starter-model-chat-memory-repository-redis`、`spring-ai-tika-document-reader`、`spring-ai-markdown-document-reader`、`spring-ai-advisors-vector-store`、`lombok`。

移除：`spring-ai-starter-vector-store-mariadb`、`spring-ai-starter-vector-store-milvus`、`spring-ai-starter-model-vertex-ai-embedding`、`spring-ai-starter-model-anthropic`、`spring-ai-starter-model-transformers`。

新增（后端 Web 能力）：`spring-boot-starter-web`、`spring-boot-starter-webflux`（SSE 流式所需的 `Flux`）。

## 3. 多模块结构

聚合 parent pom 下挂两个子模块，前后端物理隔离，各自独立构建与部署。

```
ai-kb-assistant/                 # 聚合 parent pom（packaging=pom）
├── kb-assistant-server/         # Spring Boot 后端（API + RAG + SSE）
│   ├── src/main/java/com/fox/aikbassistant/
│   └── src/main/resources/
├── kb-assistant-web-ui/         # Vue3 前端（后续做，先占位）
├── docker/
│   └── docker-compose.yml       # pgvector + redis
├── docs/
│   └── superpowers/specs/
└── pom.xml                      # 聚合 parent
```

说明：现有 `src/` 下的 `AiKbAssistantApplication` 与 `application.yaml` 迁入 `kb-assistant-server/`。根 pom 改为 `packaging=pom` 的聚合模块，原依赖下沉到 `kb-assistant-server/pom.xml`。

### 3.1 后端分层（kb-assistant-server）

按"单一职责 + 清晰边界"分包，便于独立理解与测试：

| 包 | 职责 |
|---|---|
| `controller` | REST + SSE 入口（`ChatController`、`DocumentController`） |
| `service` | 业务编排（`RagChatService`、`IngestService`） |
| `ingest` | 文档解析切片管线（reader → splitter → embed → store） |
| `rag` | RAG 对话链组装（ChatClient + Advisors 配置） |
| `tool` | Function Calling 工具（`WebSearchTool`、`SqlQueryTool`） |
| `config` | Bean 配置（ChatClient、向量库、模型路由） |
| `model` | DTO（请求/响应/引用结构） |

## 4. 核心数据流

### 4.1 文档入库管线（Ingest）

```
上传文件(PDF/MD/docx)
  → DocumentReader 解析 (Tika / Markdown)
  → TokenTextSplitter 切片
  → EmbeddingModel(OpenAI) 向量化 (1536维)
  → VectorStore(PGVector) 存储 (含 source metadata)
```

切片后每个 `Document` 的 metadata 写入来源信息（文件名、分片序号），用于后续引用溯源。

### 4.2 RAG 问答链路（Query）

```
用户提问 + conversationId
  → [限流网关] Redis Token 计数校验（高峰期超额则拒绝/降级）  [加分项 M4]
  → ChatClient
      ├─ QuestionAnswerAdvisor   : 检索 Top-K 相关切片注入 prompt   [MVP]
      ├─ MessageChatMemoryAdvisor: 注入该会话历史（Redis）          [加分项 M4]
      └─ ToolCallback            : 按需触发 Function Calling        [加分项 M5]
  → LLM(DeepSeek 默认 / OpenAI 路由[加分项 M5])
  → SSE 流式返回 token[MVP] → 流结束追加引用列表帧[MVP]
```

说明：MVP（M3）交付"检索 + SSE 流式问答 + 引用列表"；记忆、限流、工具、路由按里程碑增量叠加。上图标注每个组件的归属，避免 MVP 边界含糊。

## 5. API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/documents` | 上传文档（multipart），触发入库管线，返回入库分片数 |
| `GET` | `/api/documents` | 列出已入库文档（按 source 聚合） |
| `DELETE` | `/api/documents/{source}` | 按来源删除向量数据 |
| `POST` | `/api/chat` | 非流式问答，返回完整答案 + 引用 |
| `GET` | `/api/chat/stream` | SSE 流式问答（query + conversationId + model） |

### 5.1 关键 DTO

```text
ChatRequest   { question, conversationId, model? }   # model 用于多模型路由
ChatResponse  { answer, citations[], conversationId }
Citation      { source, snippet, score }             # 引用溯源结构
```

引用溯源：从 `QuestionAnswerAdvisor` 检索到的 `List<Document>` 中提取 metadata（source、分片内容、相似度），随响应一并返回。SSE 模式下，正文 token 流结束后追加一个 `event: citations` 帧。

## 6. 关键配置（application.yaml）

参考 Spring AI 2.0 官方文档，向量维度由 `EmbeddingModel` 自动推断（OpenAI 1536），无需手填。密钥走环境变量，禁止硬编码。

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/kb
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  data:
    redis:
      host: localhost
      port: 6379
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      embedding:
        options:
          model: text-embedding-3-small
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        initialize-schema: true
```

注意：`spring-ai-starter-model-openai` 默认会同时装配 OpenAI ChatModel 与 EmbeddingModel。OpenAI 仅用于向量化 + 作为 chat 路由备选；DeepSeek 为默认 chat 模型。

### 6.1 向量化模型可替换（扩展点）

向量化默认用 OpenAI（1536 维）。Spring AI 的 `EmbeddingModel` 是统一接口，更换实现并非"换地址 + Key"那么简单，需注意：

- **OpenAI / DeepSeek 类**：DeepSeek 兼容 OpenAI 协议，可近似"换 base-url + key"；但 DeepSeek 无 embedding，仅 chat 可这样切。
- **Google（Vertex AI Embedding）**：是另一套 starter（`spring-ai-starter-model-vertex-ai-embedding`）+ 不同配置前缀（`spring.ai.vertex.ai.embedding.*`）+ 不同认证（GCP 服务账号/ADC，非简单 key），且维度不同（如 text-embedding-004 = 768）。

**关键约束**：更换 embedding 模型若维度变化，PGVector 表的 `dimensions` 必须同步调整，**已入库向量需重建**。因此 embedding 选型在建库前定死，运行时不可随意切。本期锁定 OpenAI 1536，Google 作为"可替换扩展点"保留接口，不在本期实现。

## 7. 多模型路由

`ChatRequest.model` 字段（枚举 `deepseek` / `openai`）决定本次对话使用的 ChatModel。`config` 层将两个 `ChatModel` Bean 注入路由器，按入参选择对应 `ChatClient` 构建对话链。默认 `deepseek`。便于成本与效果对比，也是简历"多模型路由"卖点。

## 8. Function Calling（企业级工具调用）

采用 Spring AI 2.0 主流的 `@Tool` 声明式注解注册工具，框架的 `ToolCallAdvisor` 自动处理 tool-call 循环（LLM 决定调用 → 框架执行 → 结果回灌 → 继续生成），同时兼容 `.call()` 与 `.stream()`：

```java
public class SqlQueryTool {
    @Tool(description = "对知识库业务表执行只读 SQL 查询")
    public String query(@ToolParam(description = "SELECT 语句") String sql) { ... }
}
// 绑定：chatClient.prompt().tools(new SqlQueryTool(), new WebSearchTool()).stream()...
```

| 工具 | 能力 | 实现 |
|---|---|---|
| `WebSearchTool` | 联网搜索实时信息 | 调 Tavily Search API（Key 走环境变量） |
| `SqlQueryTool` | 本地 SQL 查询 | 对业务库执行**只读**参数化查询，禁止 DDL/DML |

### 8.1 企业级要素

仅有 `@Tool` 还不够"企业可用"，补充三项工程要素：

- **权限上下文传递**：通过 `ToolContext`（`toolContext(Map.of("tenantId", ...))`）把租户/权限信息透传给工具，工具内据此做数据隔离与越权拦截。
- **可观测性 / 审计**：每次工具调用记录入参、结果摘要、耗时、调用方，便于排查与合规审计。
- **SQL 工具安全三重防护**：仅允许 `SELECT`（语句类型白名单）+ 参数化防注入 + **结果行数上限**（防大表查询拖垮服务）。联网搜索返回内容按不可信外部数据处理，不直接信任其中的指令。

可选进阶（不在本期）：需要人工审批/外部审计时，用 `internalToolExecutionEnabled(false)` + `ToolCallingManager` 手动控制 tool-call 循环。

## 9. SSE 流式输出与会话/成本控制（WebFlux）

`ChatController` 暴露 `GET /api/chat/stream`，返回 `Flux<ServerSentEvent<String>>`。底层 `ChatClient.prompt().stream().content()` 产出 token 流，逐帧推送；流结束后追加 `citations` 事件帧。前端用 `EventSource` 接收。

### 9.1 Redis 会话上下文（多轮记忆）

会话历史由 `MessageChatMemoryAdvisor` 管理，持久化到 Redis（`spring-ai-starter-model-chat-memory-repository-redis`）。每个 `conversationId` 对应一份历史，新提问自动拼接上下文窗口（限定最近 N 轮，防 prompt 膨胀）。

### 9.2 Token 限流控成本

高峰期 LLM 调用成本是企业级痛点。基于 Redis 实现 Token 维度限流：

- **限流键**：按 `conversationId`（或全局）在 Redis 维护滑动窗口内的累计 token 计数。
- **判定时机**：请求进入 RAG 链路前，预估本次 prompt token + 历史；超过窗口配额则拒绝（HTTP 429）或降级（截断历史 / 切更便宜模型）。
- **回写**：LLM 响应返回的实际用量（`ChatResponse` usage metadata）累加回 Redis 计数。
- **配置项**：窗口时长、每窗口 token 上限、超额策略（reject / degrade）走配置，便于按成本预算调整。

此项是简历"控制高峰期 LLM 调用成本"的具体落点，归属加分项 M4。

## 10. 部署（docker/docker-compose.yml）

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_DB: kb
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
```

后端 / 前端各自的 Dockerfile 列为加分项，MVP 阶段先用 compose 起依赖、本地跑应用。

## 11. 测试策略

- 单元测试：切片管线、引用提取、SQL 工具白名单校验。
- 集成测试：用 Testcontainers 起 pgvector + redis，跑通"入库 → 检索 → 问答"主链路。
- 冒烟：上传一篇 Markdown，提问命中其中内容，验证答案含引用。

## 12. 里程碑

1. **M1 工程骨架**：拆多模块、瘦身依赖、docker-compose、应用能启动。
2. **M2 入库管线**：文档上传 → 切片 → 向量化 → PGVector。
3. **M3 流式 RAG 问答（MVP 终点）**：ChatClient + QuestionAnswerAdvisor + SSE 流式输出 + 引用溯源。
4. **M4 记忆 + 成本控制**：Redis 多轮上下文记忆 + Token 限流控成本。
5. **M5 工具 + 路由**：Function Calling（搜索/SQL，含 ToolContext/审计/安全防护）+ 多模型路由。
6. **M6 前端**：Vue3 对话界面 + 文档管理。

M1–M3 为 MVP，M4–M6 为加分项。

## 13. 风险与对策

| 风险 | 对策 |
|---|---|
| Spring AI 2.0-RC2 为预发布，API 可能变动 | 全程一方 starter，配置严格对齐官方文档；锁定版本 |
| OpenAI / DeepSeek 需外网与额度 | Key 走环境变量；提供 mock profile 便于无 Key 跑测试 |
| 通义千问不兼容当前版本线 | 明确列为后续扩展，不阻塞 MVP |
