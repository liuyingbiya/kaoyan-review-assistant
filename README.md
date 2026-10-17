# 考研复习助手（RAG）

![Java](https://img.shields.io/badge/Java-8-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-6DB33F)
![MyBatis](https://img.shields.io/badge/MyBatis-2.3.2-red)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1)
![Vue](https://img.shields.io/badge/Vue-3-42B883)
![tests](https://img.shields.io/badge/tests-76%20passing-brightgreen)
![license](https://img.shields.io/badge/license-MIT-blue)

一个按科目组织的考研复习问答应用。把各科复习资料灌进知识库，提问时**先检索最相关的片段，再交给大模型回答**，并把引用的原文一起返回，可以点开核对 —— 让答案有出处，而不是让模型自由发挥。

```
公共课 ─ 英语（英语一 / 英语二）
       ─ 数学（数学一 / 数学二 / 线性代数 / 概率论与数理统计）
       ─ 政治
专业课 ─ 408（数据结构 / 操作系统）
```

14 个科目节点，其中 9 个可直接提问。

---

## 它能做什么

| 能力 | 说明 |
|---|---|
| **按科目隔离的问答** | 政治的提问不会捞到操作系统的片段，检索强制限定 `subject_code` |
| **流式输出** | 边生成边显示，实测首字延迟 666ms（DeepSeek 真实调用） |
| **回答带引用** | 每段引用显示文档名、第几段、相关度分数，可展开核对原文 |
| **多轮上下文** | 追问自动带最近 3 轮历史，"那怎么预防" 能接上 |
| **资料管理** | 粘贴文本或上传 `.txt/.md`，自动切分入库（400 字一片、80 字重叠） |
| **无 Key 可降级** | 没配 API Key 时走本地模拟模式，检索与 SSE 全链路仍可测 |

---

## 技术栈

| 层 | 选型 | 为什么 |
|---|---|---|
| 后端 | Spring Boot 2.7 · MyBatis · JDK 8 | 分层清晰，Controller / Service / Mapper |
| 数据库 | MySQL 8 | 用 **ngram 全文索引**做中文检索 |
| 检索 | `MATCH ... AGAINST` + 三级降级 | 走倒排索引、有相关度分数，可排序取 Top-K |
| 大模型 | DeepSeek（OpenAI 兼容） | `stream=true` 流式返回 |
| 协议 | SSE（`SseEmitter` + `EventSource`） | 单向推送、走普通 HTTP、自带断线重连 |
| 前端 | Vue 3 · Element Plus | 免构建，产物打包进 jar，单端口启动 |
| 测试 | Node 脚本，76 项断言 | 接口 35 + 前端界面 22 + Markdown 渲染 19 |
| 部署 | Docker 多阶段构建 + compose | 一条命令起应用与数据库 |

---

## 架构

```
                     ┌──────────────────────────────────────────┐
  浏览器              │  Vue 3 + Element Plus（免构建）           │
  ──────────────────► │  · 科目树  · 会话列表                    │
                     │  · 对话（流式 + 引用）  · 资料管理        │
                     └───────────────┬──────────────────────────┘
                          REST + SSE │
                     ┌───────────────▼──────────────────────────┐
                     │  Controller   Subject / Chat / Kb / Session│
                     └───────────────┬──────────────────────────┘
                     ┌───────────────▼──────────────────────────┐
                     │  Service                                  │
                     │   ChatService  对话编排（检索→Prompt→流式）│
                     │   RetrievalService  检索 + 三级降级       │
                     │   KbService    切分入库（事务）           │
                     │   LlmClient    流式调用（读 SSE 行）      │
                     │   SeedRunner   启动自动导入（幂等）       │
                     └────────┬──────────────────┬──────────────┘
                     ┌────────▼───────┐  ┌───────▼──────────────┐
                     │ MyBatis/Mapper │  │ DeepSeek API          │
                     └────────┬───────┘  └──────────────────────┘
                     ┌────────▼─────────────────────────────────┐
                     │ MySQL 8                                  │
                     │  subject / kb_doc / kb_chunk(ngram索引)  │
                     │  chat_session / chat_msg                 │
                     └──────────────────────────────────────────┘
```

---

## 3 分钟跑起来

```powershell
# 1. 建库（4 张表 + 14 个科目）
cd backend
mysql -uroot -proot --default-character-set=utf8mb4 -e "source schema.sql"

# 2. 启动（有 Key 走真实模型，没有则走模拟模式）
cd ..
.\run.ps1 -Key "sk-你的DeepSeekKey"

# 3. 打开
#    http://localhost:8081
```

首次启动会自动导入 `seed/` 下的 9 篇资料（按「科目 + 标题」查重，不会重复导入）。

> ⚠️ 必须用 **JDK 8**。`kb_chunk.content` 的全文索引必须带 `WITH PARSER ngram`，
> 否则中文按整段当一个词，搜「死锁」命中不了。

---

## 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/subjects` | 科目树（三级） |
| GET | `/api/chat/stream?subjectCode=&question=&sessionId=` | 流式问答（SSE） |
| GET | `/api/chat/status` | 当前模型状态（是否 mock） |
| GET | `/api/kb/docs?subjectCode=` | 该科目资料列表 |
| POST | `/api/kb/import` | 粘贴文本导入 |
| POST | `/api/kb/upload` | 上传 `.txt/.md` |
| DELETE | `/api/kb/docs/{id}` | 删除资料（片段级联删除） |
| GET | `/api/sessions?subjectCode=` | 该科目会话列表 |
| GET | `/api/sessions/{id}/messages` | 会话消息 |
| DELETE | `/api/sessions/{id}` | 删除会话 |

SSE 事件（每行一个 JSON）：

```
data: {"type":"meta","sessionId":1,"model":"deepseek-chat","mock":false}
data: {"type":"delta","text":"死锁"}
data: {"type":"citations","items":[{"docTitle":"操作系统核心考点","seq":4,"score":2.13}]}
data: {"type":"done","chars":220}
```

完整契约见 [`docs/前端接口文档.md`](docs/前端接口文档.md)。

---

## 关键设计

### 1. 为什么用 SSE 而不是 WebSocket

模型生成一段回答要几秒到几十秒，等全部生成完再返回，用户会盯着空白页。

选 SSE 而不是 WebSocket：我们只需要**服务端单向推**；SSE 走普通 HTTP、不用协议升级、浏览器 `EventSource` 自带断线重连。代价是 `EventSource` 只能发 GET，所以问题放在 query 里。

### 2. 阻塞调用必须移出请求线程

`LlmClient` 读大模型响应是阻塞的（要一直读到流结束）。直接在 Controller 线程里做，几个并发就把 Tomcat 线程池占满。所以 `ChatService` 用固定大小线程池跑流式任务，Controller 立刻返回 `SseEmitter`。

### 3. 中文检索为什么必须用 ngram

MySQL 默认分词器按空格/标点切词，「死锁的四个必要条件」会被当成一个整体词，搜「死锁」命中不了。`WITH PARSER ngram` 按 2-gram 切（死锁 / 锁的 / 的四…），中文检索才有意义。

用 `MATCH ... AGAINST` 而不是 `LIKE '%x%'`：后者前置通配符用不了索引、要全表扫描，且没有相关度概念，无法排序取 Top-K。

### 4. 三级降级，保证「问了总有反馈」

1. `MATCH ... AGAINST` 全文索引（最准）
2. 没命中 → 从问题里抽关键词逐个 `LIKE` 兜底
3. 还是空 → 返回空，让模型明确回答「资料里没有提到」

### 5. 防止幻觉的两个手段

- **Prompt 里下死命令**：只能依据资料回答，没有就说没有
- **返回引用**：每段带文档名、第几段、相关度，用户可核对

### 6. 切分参数

- `chunk-size = 400`：太小上下文不完整，太大检索不精准且浪费 token
- `chunk-overlap = 80`：防止一句话正好被切断
- 优先在「。！？；换行」处断开，不把一句话从中间劈开

### 7. 前端为什么免构建

页面就一个、组件 4 个，没有复杂模块图。引入 Vite 要付出「装 Node → 装依赖 → build → 配 Nginx」的成本，换来的是用不上的 Tree-shaking。

现在前端产物由 Maven 打进 jar 的 `static/`，一个 `java -jar` 就把前后端都起了。组件化、props/emit、响应式、生命周期一样不少。

---

## 测试

```powershell
node tests\verify-api.js        # 接口 35 项
node tests\verify-markdown.js   # Markdown 渲染 19 项（含 4 项 XSS 转义校验）
cd tests; node verify-ui.js     # 前端界面 22 项（jsdom 真加载页面 + 模拟点击）
```

共 **76 项断言**，覆盖：

- 科目树结构（14 节点 / 2 个顶级分类 / 9 个可提问叶子）
- SSE 四类事件按 `meta → delta → citations → done` 顺序到达
- 检索命中正确性（问死锁必须命中《操作系统核心考点》）
- **跨科目隔离**（政治上问死锁，不能捞到操作系统资料）
- 多轮会话复用与消息落库
- 前端挂载、科目切换交互、无 JS 报错
- Markdown 渲染与 **XSS 转义**（模型输出不可信，必须先转义再替换）

---

## 目录结构

```
ai项目/
├─ backend/            后端（Spring Boot，JDK 8）
│  ├─ schema.sql       建库脚本（ngram 索引 + 14 个科目）
│  └─ src/main/java/com/aikb/{controller,service,mapper,model,config}
├─ frontend/           前端（Vue 3，免构建）
│  ├─ index.html
│  ├─ css/app.css
│  ├─ js/{api,app,markdown}.js
│  ├─ js/components/   subject-tree / session-list / chat-panel / kb-manager
│  └─ vendor/          vue / element-plus / 中文语言包（本地文件，不依赖 CDN）
├─ seed/               初始资料，子目录名 = 科目编码
├─ tests/              3 个验证脚本
├─ docs/               前端接口文档 / 项目讲解 / 部署与运行
├─ run.ps1             一键启动
└─ Dockerfile + docker-compose.yml
```

---

## 已知不足

1. **只做关键词检索，没有向量检索。** 语义相近但用词不同的问法会漏召回（问「怎么避免库存超卖」，资料里写的是「条件更新扣库存」）。要解决需接 embedding + 向量库（pgvector / Milvus）。
2. **没有鉴权。** 能访问 8081 的人都能读写资料库。
3. **没有限流。** 大模型按 token 计费，被刷会造成费用损失。
4. **会话没有分页。** 消息多了会一次性全查出来。
5. **单实例。** 线程池是进程内的，横向扩容需要改造。

> 这几条是主动写出来的，不是没意识到。

---

## License

[MIT](LICENSE)
