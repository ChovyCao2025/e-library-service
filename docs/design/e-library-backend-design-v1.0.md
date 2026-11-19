# E-Library 电子图书馆服务 · 后端设计与实施文档 v1.0

> 用途：直接指导开发，可整体交给大模型在全新上下文中按里程碑实现。本文档是唯一事实来源：§1.4 的“固定决策”不得更改；未覆盖的细节按 §2 假设处理；实现顺序见 §14；禁止项见 §15。

## 0. 章节导航

1 目标与固定决策 ｜ 2 假设 ｜ 3 总体架构 ｜ 4 领域模型 ｜ 5 工程结构 ｜ 6 MySQL ｜ 7 Redis ｜ 8 核心流程 ｜ 9 Elasticsearch ｜ 10 API ｜ 11 错误模型 ｜ 12 高可用与降级 ｜ 13 可观测性/配置/测试 ｜ 14 里程碑 ｜ 15 禁止项 ｜ 16 README 提纲与取舍

## 1. 目标与固定决策

### 1.1 业务本质

数字内容借阅 = “并发许可（license seat）”。每本书有 total\_copies 个可同时借阅的席位：借出占 1 席，归还或到期释放 1 席。这与电商“库存扣减”同构，热门新书、考试周教材就是“秒杀商品”。因此：

- **借阅链路按秒杀设计**：入口限流削峰 → 本地售罄标记 → Redis Lua 预占 → MySQL 条件更新终裁 → 事件异步扩散。
- **浏览/搜索链路按读多写少设计**：多级缓存 + Elasticsearch。
- **硬约束：任何情况下不得超借**（已借出数 ≤ total\_copies），不得丢失借阅记录。

### 1.2 范围

- **范围内**：浏览书籍（关键词搜索/分类筛选/排序/游标分页）、书籍详情、借阅、归还、查看我当前在借书籍、到期自动归还、管理员最小接口（建书/改书/上下架/调库存/重建索引）。
- **范围外**：认证与用户系统、DRM 与在线阅读器、续借、预约排队、推荐、计费、前端。这些列入 §16 扩展点。

### 1.3 非功能目标（压测验收线）

| 指标 | 目标 |
| --- | --- |
| 借阅入口吞吐 | 单热点书 ≥ 20k RPS 请求进入，绝大多数在 Redis 层被拒绝；成功落库速率 ≈ 库存周转速率 |
| 借阅延迟 | P99 < 150ms（成功路径），拒绝路径 P99 < 20ms |
| 详情/列表延迟 | 缓存命中 P99 < 50ms；ES 检索 P99 < 200ms |
| 可用性 | 99.95%；单个 Redis 节点/ES 节点/MQ 节点/应用实例故障不影响服务 |
| 正确性 | 并发压测下成功借出数严格等于库存；同一幂等键重复请求只产生一条借阅 |
| 数据丢失 | 已返回成功的借阅/归还不丢（MySQL 半同步或 MGR） |

### 1.4 固定决策（ADR，不得更改）

| # | 决策 | 理由 |
| --- | --- | --- |
| 1 | Java 21 + Spring Boot 3.3 + MyBatis（显式 SQL）+ HikariCP + MapStruct | 热点 SQL 必须可控，不用 JPA 的隐式行为 |
| 2 | **模块化单体 + 六边形架构 + DDD**，限界上下文 catalog / lending / search；同一 jar 用 app.role=api\|worker\|all 区分角色部署 | 避免早期微服务的运维成本，同时边界清晰、可按上下文拆分 |
| 3 | **MySQL 8.0 是唯一事实源**；Redis 7 Cluster 只做预占/缓存/幂等；ES 8 只做检索读模型，**绝不存库存、不做事实源** | 库存变更频繁，写 ES 代价高且最终一致，不适合做库存 |
| 4 | 借阅防超借三层：本地售罄标记（Caffeine）→ Redis Lua 预占 → MySQL 条件 UPDATE 终裁 | 前两层只为削峰，第三层保证正确性；Redis 丢数据也不会超借 |
| 5 | 事件使用 **Transactional Outbox + RocketMQ 5**（普通消息），消费者必须幂等 | 无分布式事务；至少一次投递 |
| 6 | 主键 Snowflake long，API 层一律用字符串表示 | 避免 JS 精度丢失；分片友好 |
| 7 | 借阅接口强制 Idempotency-Key 请求头 | 网络重试/重复点击安全 |
| 8 | 认证由网关完成，向后端透传 X-User-Id；本服务信任该头 | 认证非本题重点 |
| 9 | 列表一律游标分页（不做 offset 深分页） | 深分页在 MySQL/ES 都昂贵 |
| 10 | 时间统一 UTC，Instant，ISO-8601；数据库 DATETIME(3) | 避免时区错误 |
| 11 | loan 表以 user\_id 为分片键设计（v1 单库，保持 sharding-ready）：所有 loan 查询都带 user\_id | 将来可直接接 ShardingSphere |
| 12 | 序列化 Redis 值用纯字符串/数字，不用 Java 序列化 | 可读、跨语言、Lua 友好 |

## 2. 假设（需求未明确处的合理假设）

- 每本书有 total\_copies 个并发借阅席位；**同一用户同一本书同时只能借 1 份**；**每用户同时最多借 5 本**；借期 **14 天**；到期**自动归还**（释放席位）。
- 不支持续借、预约排队（热点书售罄返回 BOOK\_SOLD\_OUT，客户端可稍后重试）。
- 只有 status=PUBLISHED 的书可见、可借；下架（OFFLINE）的书不可借，但已借出的借阅记录保留，可正常归还。
- 管理员接口由网关用角色鉴权，本服务只校验头 X-Role=ADMIN。
- 借阅成功响应中给出 dueAt（读取权限截止时间）；实际内容分发不在范围内。
- 规模假设：书目 ≤ 1000 万；用户 ≤ 1 亿；活跃借阅 ≤ 5 亿行（需要分片）；热点书同时借阅者 ≤ 数万。

## 3. 总体架构

```
Client ─► CDN / API Gateway（JWT 校验、IP+用户限流、WAF）
              │ X-User-Id / X-Role
              ▼
   ┌────────────── App Cluster（无状态，≥3 实例，跨 2 个 AZ，K8s HPA）────────────┐
   │ role=api   : catalog-api / lending-api / search-api （REST）               │
   │ role=worker: outbox-relay / MQ consumers / schedulers（到期归还、对账、清扫）│
   └──────┬───────────────┬───────────────┬───────────────┬────────────────────┘
          ▼               ▼               ▼               ▼
   Redis Cluster     MySQL 8.0        RocketMQ 5       Elasticsearch 8
   3主3从 AOF/1s    MGR 或 1主2从     3 Broker 主从     3 节点 / 3 分片 1 副本
   预占/缓存/幂等   事实源+outbox     事件总线         检索读模型
```

**四条数据路径**

1. **借阅/归还（写）**：见 §8，Redis 预占 → MySQL 事务（loan + stock + quota + outbox）→ 事务后释放预占标记。
2. **详情（读）**：Caffeine(L1, 5s) → Redis(L2, 10min±jitter) → MySQL；防穿透（空值缓存）、防击穿（单飞/互斥）、防雪崩（TTL 抖动）。
3. **列表（读）**：ES 检索返回 bookId 列表与摘要 → Redis MGET 批量补充实时可借数量（缺失回源 MySQL 批量查）。
4. **同步（异步）**：MySQL → outbox 表 → relay 发 RocketMQ → ES indexer / Redis 投影器 / 缓存失效器（均幂等）。

**角色拆分**：同一镜像，api 实例水平扩展承担流量；worker 实例 ≥2（用分布式锁/分片任务保证单次只有一个在跑扫描任务，MQ 消费者天然多实例）。

### 3.1 微服务架构图（目标拓扑）

v1 是模块化单体（同一镜像按 app.role 启动 api/worker）。下图是拆分后的目标微服务拓扑，服务边界与 §4 的限界上下文一一对应，数据按上下文独占。

&#91;embedded content: 微服务目标拓扑 · 4 个服务 + 5 个数据组件\]

读图：lending-service 是核心域，独占借阅库与 Redis 预占键；catalog 与 search 只通过事件和只读快照与它交互。拆分时把各上下文的 application.facade 换成 RPC 实现、把 outbox 表随上下文迁到各自库即可，业务代码不变。

| 服务 | 对应上下文 | 独占数据 | 建议拆分顺序 |
| --- | --- | --- | --- |
| search-service | search | ES 索引 | 1（只读、无事务，风险最低） |
| catalog-service | catalog | book、category 库 | 2 |
| lending-service + worker | lending | 借阅库、Redis 预占键 | 3（最后拆，改动最敏感） |

## 4. 领域模型（DDD）

### 4.1 限界上下文与关系

| 上下文 | 类型 | 职责 | 聚合 |
| --- | --- | --- | --- |
| catalog | 支撑域 | 书目主数据管理 | Book |
| lending | **核心域** | 借出/归还/库存/配额 | LendingStock、Loan、UserLendingAccount |
| search | 支撑域（读模型） | 书目检索，订阅 catalog 事件构建 ES | 无聚合 |
| shared | 共享内核 | id、时间、分页、错误基类、outbox、幂等 | — |

**关系**：lending 通过端口 BookCatalogGateway（防腐层）获取 BookSnapshot(bookId, status)，v1 实现为进程内调用 catalog 的 application 查询门面；拆服务时换成 RPC 实现。lending 不得 import catalog.domain。search 只消费事件与 catalog 查询门面，不得直接读 catalog 的表。

### 4.2 统一语言

| 术语 | 含义 |
| --- | --- |
| Book 书目 | 一本电子书/期刊的元数据，含 format=EBOOK\|JOURNAL |
| Seat 席位 | 一个并发借阅许可；total\_copies = 席位总数 |
| LendingStock 借阅库存 | 某书的 total/available 席位 |
| Loan 借阅记录 | 一次借阅，BORROWED → RETURNED |
| Reservation 预占 | Redis 层对席位与配额的临时占用（技术概念，非领域概念，不出现在领域层） |

### 4.3 聚合设计

**Book（catalog）**

- 字段：bookId, isbn(唯一), title, subtitle, authors(List\<String>), publisher, categoryId, language, description, coverUrl, publishedDate, format, status(DRAFT|PUBLISHED|OFFLINE), version, createdAt, updatedAt。
- 不变式：isbn 唯一；title 非空且 ≤200；只有 PUBLISHED 可借可见。
- 行为：create(), updateInfo(), publish(), offline()。状态机：DRAFT→PUBLISHED⇄OFFLINE。每次变更 version+1 并产生 BookPublished/BookUpdated/BookOfflined 事件。

**LendingStock（lending）**——书的席位库存

- 字段：bookId, totalCopies, availableCopies, version。
- 不变式：0 ≤ available ≤ total。
- 行为：lend()（available=0 抛 BookSoldOutException）、restore()（available 已等于 total 抛 StockInvariantViolation）、resize(newTotal)（available += newTotal - total，结果 <0 抛异常）。
- 说明：并发下的最终保证不靠内存对象，靠 §6 的条件 UPDATE；领域对象用于单线程规则表达、管理员调库存与单元测试。

**Loan（lending）**

- 字段：loanId, userId, bookId, status(BORROWED|RETURNED), borrowedAt, dueAt, returnedAt, returnReason(MANUAL|EXPIRED), idemKey, version。
- 工厂：Loan.borrow(loanId, userId, bookId, idemKey, policy, now) → dueAt = now + policy.loanDays。
- 行为：markReturned(reason, now) → 返回 true 表示发生了状态迁移；已是 RETURNED 返回 false（幂等，不抛错）。
- 状态机：BORROWED → RETURNED（终态）。
- 派生：activeKey = status==BORROWED ? 1 : null，用于唯一约束“同用户同书只能有一条在借”。

**UserLendingAccount（lending）**——用户借阅配额

- 字段：userId, activeCount, version。不变式：0 ≤ activeCount ≤ policy.maxActiveLoansPerUser。
- 行为：reserve(max)、release()。持久化时同样使用条件 UPDATE。

**BorrowPolicy（值对象，来自配置）**：maxActiveLoansPerUser=5, loanDays=14, allowDuplicateBook=false。

**跨聚合一致性**：Loan、LendingStock、UserLendingAccount 在同一上下文、同一 MySQL 库，借/还由应用服务在**一个本地事务**内同时修改三者（这是显式的、有理由的例外：它们共同维护“席位守恒”这一不变式）。跨上下文与 ES/Redis 一律最终一致。

### 4.4 领域事件

信封字段：eventId(snowflake), type, aggregateType, aggregateId, occurredAt, version, payload(JSON)。

| 事件 | 产生方 | payload | 消费者 |
| --- | --- | --- | --- |
| BookPublished / BookUpdated / BookOfflined | catalog | 书目快照 + version | search-indexer、缓存失效器 |
| BookBorrowed | lending | loanId,userId,bookId,dueAt | 指标/审计（预留） |
| BookReturned | lending | loanId,userId,bookId,reason | Redis 预占释放修复器（兜底）、指标 |
| StockResized | lending | bookId,total,available | Redis 库存重置器 |

### 4.5 领域异常（纯 Java，不依赖框架）

BookNotFound, BookNotBorrowable, BookSoldOut, AlreadyBorrowed, LoanLimitExceeded, LoanNotFound, LoanNotOwned, StockInvariantViolation, DuplicateIsbn, ConcurrentModification。映射到 HTTP 见 §11。

## 5. 工程结构

```
com.elibrary
 ├─ shared/        kernel: id(Snowflake), time(ClockProvider), paging(Cursor), error(BizException, ErrorCode),
 │                 outbox(OutboxEvent, OutboxRelay), idempotency, web(TraceFilter, UserContext)
 ├─ catalog/       domain{model,event,repository,exception} application{command,query,service,facade}
 │                 infrastructure{persistence,cache,mq} interfaces{rest,dto}
 ├─ lending/       domain{model(Loan,LendingStock,UserLendingAccount,BorrowPolicy,LoanStatus),event,repository,exception}
 │                 application{command,query,service(BorrowService,ReturnService,LoanQueryService,ExpireLoansService),port}
 │                 infrastructure{persistence,redis(LuaScripts,RedisReservationAdapter),mq,job}
 │                 interfaces{rest,dto}
 ├─ search/        application{SearchService,IndexerService} infrastructure{es,mq} interfaces{rest}
 └─ ElibraryApplication
```

**分层规则（用 ArchUnit 测试强制，违反则构建失败）**

1. domain 层：纯 Java，禁止出现 Spring/MyBatis/Redis/Jackson 注解与依赖。
2. application 层：只依赖本上下文 domain 与 port 接口；编排用例、定义事务边界；**@Transactional 只能出现在 application 层，事务内只允许数据库操作（含 outbox 插入），禁止 Redis/MQ/ES/HTTP 调用**。
3. infrastructure 层：实现 port 与 repository 接口；PO（持久化对象）与领域对象分离，用 MapStruct 转换。
4. interfaces 层：Controller 只做参数校验与 DTO 转换，调用 application；统一异常处理 @RestControllerAdvice。
5. 上下文之间只能经 application.facade 或事件交互，禁止跨上下文访问 domain/infrastructure。
6. 命名：Command（写入口参数）、Query（读入口参数）、Dto（接口出入参）、Po（表映射）、Port（出站接口）。

## 6. MySQL 设计（唯一事实源）

### 6.1 通用约定

InnoDB、utf8mb4、事务隔离级别统一 **READ COMMITTED**（减少间隙锁）；主键为 Snowflake 的 BIGINT UNSIGNED；不建外键；时间 DATETIME(3) 存 UTC；可变聚合带 version 列；所有 DDL 放 Flyway 迁移脚本 V1\_\_init.sql。

### 6.2 表结构

```sql
CREATE TABLE category (
  category_id BIGINT UNSIGNED NOT NULL, name VARCHAR(50) NOT NULL, parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book (
  book_id BIGINT UNSIGNED NOT NULL,
  isbn VARCHAR(20) NOT NULL,
  title VARCHAR(200) NOT NULL,
  subtitle VARCHAR(200) NULL,
  authors JSON NOT NULL,
  publisher VARCHAR(100) NULL,
  category_id BIGINT UNSIGNED NOT NULL,
  language VARCHAR(10) NOT NULL,
  description TEXT NULL,
  cover_url VARCHAR(500) NULL,
  published_date DATE NULL,
  format VARCHAR(10) NOT NULL,          -- EBOOK / JOURNAL
  status VARCHAR(10) NOT NULL,          -- DRAFT / PUBLISHED / OFFLINE
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (book_id),
  UNIQUE KEY uk_isbn (isbn),
  KEY idx_category_status (category_id, status, book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE lending_stock (
  book_id BIGINT UNSIGNED NOT NULL,
  total_copies INT NOT NULL,
  available_copies INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (book_id),
  CONSTRAINT ck_stock CHECK (available_copies >= 0 AND available_copies <= total_copies)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_lending_account (
  user_id BIGINT UNSIGNED NOT NULL,
  active_count INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT ck_active CHECK (active_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE loan (
  loan_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,      -- 分片键
  book_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(10) NOT NULL,           -- BORROWED / RETURNED
  active_key TINYINT NULL,               -- BORROWED 时为 1，归还后置 NULL（唯一索引忽略 NULL）
  idem_key VARCHAR(64) NOT NULL,
  borrowed_at DATETIME(3) NOT NULL,
  due_at DATETIME(3) NOT NULL,
  returned_at DATETIME(3) NULL,
  return_reason VARCHAR(10) NULL,        -- MANUAL / EXPIRED
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (loan_id),
  UNIQUE KEY uk_user_book_active (user_id, book_id, active_key),
  UNIQUE KEY uk_user_idem (user_id, idem_key),
  KEY idx_user_status_time (user_id, status, borrowed_at, loan_id),
  KEY idx_book_active (book_id, active_key, user_id),
  KEY idx_status_due (status, due_at, loan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
  id BIGINT UNSIGNED NOT NULL,           -- 即 eventId
  aggregate_type VARCHAR(30) NOT NULL,
  aggregate_id BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 0,     -- 0 NEW, 1 SENT
  created_at DATETIME(3) NOT NULL, sent_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**索引设计说明**

- uk\_user\_book\_active：同一用户同一本书只能有一条在借记录，由数据库强制（active\_key 技巧：NULL 不参与唯一冲突）。
- uk\_user\_idem：幂等键的持久化兜底，Redis 幂等键丢失也不会重复借阅。
- idx\_user\_status\_time：“我的在借”游标分页，覆盖 where+order by。
- idx\_book\_active：缓存预热时查某书当前借阅者。
- idx\_status\_due：到期扫描。

### 6.3 关键 SQL（终裁语句，必须原样使用条件更新）

```sql
-- 借阅：占用配额（先保证行存在）
INSERT IGNORE INTO user_lending_account(user_id, active_count, version, updated_at) VALUES (?, 0, 0, ?);
UPDATE user_lending_account SET active_count = active_count + 1, version = version + 1, updated_at = ?
 WHERE user_id = ? AND active_count < ?;                       -- 影响 0 行 => LOAN_LIMIT_EXCEEDED
-- 借阅：扣库存（超借的最后防线）
UPDATE lending_stock SET available_copies = available_copies - 1, version = version + 1, updated_at = ?
 WHERE book_id = ? AND available_copies > 0;                   -- 影响 0 行 => BOOK_SOLD_OUT
-- 借阅：写记录（唯一键冲突分别映射 ALREADY_BORROWED / 幂等重放）
INSERT INTO loan(loan_id,user_id,book_id,status,active_key,idem_key,borrowed_at,due_at,version,created_at,updated_at)
VALUES (?,?,?,'BORROWED',1,?,?,?,0,?,?);
-- 归还：状态迁移（只有一个并发者能成功）
UPDATE loan SET status='RETURNED', active_key=NULL, returned_at=?, return_reason=?, version=version+1, updated_at=?
 WHERE loan_id=? AND user_id=? AND status='BORROWED';           -- 影响 0 行 => 已被归还，视为幂等成功
-- 归还：释放库存与配额
UPDATE lending_stock SET available_copies = available_copies + 1, version=version+1, updated_at=? WHERE book_id=? AND available_copies < total_copies;
UPDATE user_lending_account SET active_count = active_count - 1, version=version+1, updated_at=? WHERE user_id=? AND active_count > 0;
-- 我的在借（游标分页，未到期）
SELECT * FROM loan WHERE user_id=? AND status='BORROWED' AND due_at > ?
   AND (borrowed_at < ? OR (borrowed_at = ? AND loan_id < ?))
 ORDER BY borrowed_at DESC, loan_id DESC LIMIT ?;
```

**加锁顺序（防死锁）**：借阅 = account → stock → loan → outbox；归还 = loan → account → stock → outbox（loan 行互不相同，不构成环）。遇到 1213 死锁或 1205 锁等待超时，在应用层重试最多 2 次（间隔 10-30ms 随机）。事务超时 3s，事务尽量短。

### 6.4 容量与演进

- **热点行**：lending\_stock 单行更新是串行点。由于 Redis 预占把成功请求数限制在库存数以内，落库 TPS ≈ 库存周转速率，v1 足够；极端热点的拆分方案（lending\_stock\_slot 分槽）列入 §16 扩展。
- **分片**：loan、user\_lending\_account 按 user\_id 分片（建议 16 库 × 16 表），lending\_stock、book 按 book\_id；所有 loan 查询均带 user\_id，保持 sharding-ready。
- **归档**：status=RETURNED 且 returned\_at 早于 180 天的 loan 由 worker 批量迁移到 loan\_history（结构相同，无唯一约束）。
- **读写分离**：列表/详情回源可走从库；“我的借阅”与借还后的查询走主库（或带 3s 读己之写标记 Redis 键 rw:{userId} 时走主库）。

## 7. Redis 设计（预占、缓存、幂等；非事实源）

### 7.1 Key 规范

Redis Cluster 的 Lua 要求所有 KEYS 在同一槽位，故使用 hash tag：书维度 {b:bookId}，用户维度 {u:userId}。

| Key | 类型 | 含义 | TTL |
| --- | --- | --- | --- |
| {b:ID}:stock | string(int) | 当前可借份数（已扣除预占） | 无，可重建 |
| {b:ID}:holders | set | 在借或预占中的 userId | 无，可重建 |
| {b:ID}:pending | zset | member=loanId:userId，score=预占过期毫秒时间戳 | 无 |
| {b:ID}:idem:{userId}:{idemKey} | string | 该幂等键对应的 loanId | 24h |
| {b:ID}:rel:{loanId} | string | 归还释放去重标记 | 24h |
| {b:ID}:warm-lock | string | 预热互斥锁 | 10s |
| {u:ID}:quota | string(int) | 用户已占用配额 | 7d |
| {u:ID}:rel:{loanId} | string | 配额释放去重标记 | 24h |
| pending:books | set | 存在 pending 预占的 bookId（sweeper 的索引） | 无 |
| book:detail:ID | string(JSON) | 详情缓存 | 10min ±20% |
| book:null:ID | string | 空值缓存（防穿透） | 60s |

**键名与纪元**：表中所有书/用户键实际形如 `{b:ID}:e{epoch}:stock`、`{u:ID}:e{epoch}:quota`，epoch 来自 MySQL 表 sys\_epoch（§12.5），应用本地缓存并在 1 秒内感知变化；stock、holders、pending、quota 键均设 7 天 TTL，由应用在每次脚本成功后以 pipeline 续期（尽力而为）。因此任何时候换纪元，旧键立刻不再被读取，7 天内自然过期，无需扫描清理。所有键名必须经统一的 RedisKeys 类构造，禁止手拼字符串。

### 7.2 Lua 脚本（放 resources/lua/，启动时 SCRIPT LOAD，运行用 EVALSHA）

**quota\_reserve.lua**（KEYS\[1\]={u:ID}:quota；ARGV\[1\]=max，ARGV\[2\]=ttl 秒）

```lua
local c = tonumber(redis.call('GET', KEYS[1]))
if c == nil then return -1 end                 -- 未预热
if c >= tonumber(ARGV[1]) then return 0 end    -- 超配额
redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], ARGV[2])
return 1
```

**quota\_release.lua**（KEYS\[1\]=quota，KEYS\[2\]={u:ID}:rel:{loanId}；ARGV\[1\]=ttl 秒）

```lua
if not redis.call('SET', KEYS[2], '1', 'NX', 'EX', ARGV[1]) then return 0 end
local c = tonumber(redis.call('GET', KEYS[1]))
if c and c > 0 then redis.call('DECR', KEYS[1]) end
return 1
```

**stock\_reserve.lua**（KEYS: 1=stock 2=holders 3=pending 4={b:ID}:idem:{uid}:{key}；ARGV: 1=userId 2=loanId 3=nowMs 4=pendingTtlMs 5=idemTtlSec）

```lua
local existing = redis.call('GET', KEYS[4])
if existing then return {2, existing} end                              -- REPLAY
local stock = redis.call('GET', KEYS[1])
if not stock then return {-1, ''} end                                  -- NOT_WARM
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return {3, ''} end  -- ALREADY_BORROWED
if tonumber(stock) <= 0 then return {0, ''} end                        -- SOLD_OUT
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('ZADD', KEYS[3], tonumber(ARGV[3]) + tonumber(ARGV[4]), ARGV[2] .. ':' .. ARGV[1])
redis.call('SET', KEYS[4], ARGV[2], 'EX', ARGV[5])
return {1, ARGV[2]}                                                    -- OK
```

**stock\_release\_failed.lua**（回滚一次**未提交**的预占；KEYS: 1=stock 2=holders 3=pending；ARGV: 1=userId 2=loanId）

```lua
if redis.call('ZREM', KEYS[3], ARGV[2] .. ':' .. ARGV[1]) == 0 then return 0 end  -- 没有预占就不能回补，防止超发
if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]) end
return 1
```

**stock\_release\_returned.lua**（归还已提交的借阅；KEYS: 1=stock 2=holders 3={b:ID}:rel:{loanId}；ARGV: 1=userId 2=ttl 秒）

```lua
if not redis.call('SET', KEYS[3], '1', 'NX', 'EX', ARGV[2]) then return 0 end   -- 去重，重复调用无副作用
if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]) end
return 1
```

**warm\_stock.lua**（KEYS: 1=stock 2=holders；ARGV: 1=available，其后为当前借阅者 userId 列表）

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1])
for i = 2, #ARGV do redis.call('SADD', KEYS[2], ARGV[i]) end
return 1
```

事务提交后确认预占：直接 `ZREM {b:ID}:pending loanId:userId`（幂等）。

**为什么回滚与归还用两个脚本**：客户端超时时无法判断 reserve 是否已执行。若回滚脚本无条件 INCR，会把库存多补回去造成缓存偏高；改为“只有 pending 中存在该预占才回补”，天然幂等且安全。配额在极端超时下可能漂移，但方向是保守的（多占），且 MySQL 配额更新才是终裁，由对账修复。

### 7.3 超时与重试

Redis 命令超时 100ms，写类脚本**不自动重试**（由上层按语义处理）；连接池 Lettuce，开启集群拓扑自动刷新与读 replica 仅用于缓存类读取（库存相关一律读主）。

### 7.4 预热（懒加载 + 互斥）

返回 NOT\_WARM(-1) 时：

1. `SET {b:ID}:warm-lock <uuid> NX PX 10000` 抢锁，抢不到则 sleep 20ms 重试最多 3 次，仍失败返回 503 SERVER\_BUSY。
2. 从 MySQL 读 `lending_stock.available_copies` 与 `SELECT user_id FROM loan WHERE book_id=? AND active_key=1`，执行 warm\_stock.lua，释放锁（对比 uuid 再删）。
3. 重试一次 reserve。用户配额同理：读 `user_lending_account.active_count`，`SET {u:ID}:quota v NX EX 604800`。
4. 发布书籍/调整库存时主动预热；运营可标记热门书提前预热。

### 7.5 热点治理

- **本地售罄标记**：Caffeine `soldOut` 缓存（最大 100k 项，TTL 500ms）。Lua 返回 SOLD\_OUT 时写入；入口先查它，命中直接返回 409，完全不访问 Redis。最多延迟 500ms 感知归还，可接受。
- **入口限流**：网关层按用户 5 rps、IP 50 rps；应用层 Sentinel 对借阅接口按 bookId 设置并发隔离（信号量 2000），超限快速失败返回 429。
- **大 key 约束**：holders 集合大小 ≤ total\_copies，管理员设置 total\_copies 上限 100000。
- 单 key 超过 Redis 单节点上限的分槽方案见 §16。

### 7.6 自愈任务（worker）

- **ReservationSweeper**（每 10s）：遍历 `pending:books`，`ZRANGEBYSCORE {b:ID}:pending -inf now LIMIT 100`；对每个 member 用 (userId, loanId) 查 MySQL 主库的 loan（禁止读从库，复制延迟会把已提交误判为不存在）：存在则只 ZREM；查询报错或超时则本轮跳过、保持预占（宁可暂时多占，不可误放）；确认不存在才执行 stock\_release\_failed + quota\_release，随后 pending 为空则 SREM pending:books。预占 TTL 默认 30s，远大于请求超时 3s。
- **StockReconciler**（每 60s 处理 warm 的热点书，每日全量抽样）：expected = MySQL.available − Redis pending 数；若无 pending 且 |redis.stock − expected| > 0 连续两轮都成立，则以 expected 覆盖并重建 holders。每次修复打 WARN 指标 `stock_reconcile_fix_total`。
- **QuotaReconciler**：同理以 `user_lending_account.active_count` 覆盖 Redis 配额。

## 8. 核心流程

### 8.0 借阅全流程图

&#91;embedded content: 借阅主流程 · 5 道前置 + 1 次 DB 事务 + 3 种结果\]

读图：主干自上而下，右侧是各层拒绝。最关键的是最下面一行的三种 DB 事务结果：只有“确定性失败”才回滚预占，“结果不确定”交给 sweeper 以 MySQL 为准裁决，避免把已提交的借阅误释放。文字版步骤见 8.1。

### 8.1 借阅 BorrowService.borrow(userId, bookId, idemKey)

```
0  校验：idemKey 非空且 <=64 字符；bookId 合法；否则 400
1  本地售罄标记命中 -> 409 BOOK_SOLD_OUT（不访问 Redis）
2  BookCatalogGateway.snapshot(bookId)（L1/L2 缓存）：不存在 -> 404；status != PUBLISHED -> 409 BOOK_NOT_BORROWABLE
3  loanId = Snowflake.next()          // 预占之前生成，写入幂等键与 pending，崩溃后可据此对账
4  quota = reserveQuota(userId)：0 -> 409 LOAN_LIMIT_EXCEEDED；-1 -> 预热后重试一次
5  stock = reserveStock(bookId, userId, loanId, idemKey)：
     1  继续
     2  REPLAY：quota_release(本次 loanId)，转入重放处理（按 uk_user_idem 查 MySQL：有则返回原 loan；无则 409 REQUEST_IN_PROGRESS，客户端 1s 后重试）
     0  quota_release；写本地售罄标记；409 BOOK_SOLD_OUT
     3  quota_release；409 ALREADY_BORROWED
    -1  预热后重试一次；仍失败 -> 降级为 DB-only 路径（§12）
6  try 本地事务 persistBorrow(...)；catch：
     uk_user_idem 冲突     -> 回滚预占（stock_release_failed + quota_release），返回已存在 loan
     库存条件更新 0 行      -> 回滚预占，使该书 Redis 键失效以重建，409 BOOK_SOLD_OUT
     配额条件更新 0 行      -> 回滚预占，409 LOAN_LIMIT_EXCEEDED
     uk_user_book_active 冲突 -> 回滚预占，409 ALREADY_BORROWED
     其他异常               -> 不立即回滚预占：先按 (userId, idemKey) 查 MySQL 主库，有记录则当作成功返回；确认无记录且属确定性失败才回滚；结果不确定（超时/断连/提交状态未知）则保留预占并返回 503，交给 sweeper 以 MySQL 裁决（§12.5）
7  提交成功后 ZREM pending（确认预占），返回 201 + Loan
```

**persistBorrow 事务**（@Transactional(isolation=READ\_COMMITTED, timeout=3)，顺序固定）：INSERT IGNORE account → UPDATE account(+1, 条件 < max) → UPDATE stock(−1, 条件 > 0) → INSERT loan → INSERT outbox(BookBorrowed)。

### 8.2 归还 ReturnService.returnLoan(userId, loanId, reason)

```
1  按 (loanId, userId) 查 loan；不存在或不属于该用户 -> 404 LOAN_NOT_FOUND（不泄露他人记录）
2  已是 RETURNED -> 直接 200（幂等）
3  本地事务：UPDATE loan(条件 status=BORROWED，0 行则视为并发已归还、直接成功) → UPDATE account(−1) → UPDATE stock(+1) → INSERT outbox(BookReturned)
4  提交后（失败仅记日志，不影响响应）：stock_release_returned + quota_release（均以 loanId 去重）；
   同时 BookReturned 事件由 redis-projector 消费者再执行一次相同脚本，作为兜底（幂等）
5  返回 200 + Loan（status=RETURNED）
```

### 8.3 到期自动归还 ExpireLoansService（worker，每 30s）

分布式锁 `job:expire`（或 ElasticJob 分片）保证同一时刻单实例执行：`SELECT loan_id,user_id FROM loan WHERE status='BORROWED' AND due_at <= now ORDER BY due_at LIMIT 500`（走 idx\_status\_due），线程池并发 8，逐条调用 returnLoan(reason=EXPIRED)；失败跳过，下一轮重试。查询“我的在借”时额外过滤 due\_at > now，所以任务延迟也不会让用户看到已到期的书。

### 8.4 管理员调库存

事务：`UPDATE lending_stock SET total_copies=?, available_copies=available_copies+(?-total_copies), version=version+1 WHERE book_id=? AND available_copies+(?-total_copies)>=0 AND ?<=100000`（0 行 -> 409 STOCK\_BELOW\_BORROWED）+ outbox(StockResized)。消费者在无 pending 时 DEL 该书 stock/holders，下一次借阅懒预热。新建书时同事务创建 lending\_stock 行（默认 0 份）。

### 8.5 书目变更与缓存/索引同步

catalog 事务内：更新 book(version+1) + outbox(BookUpdated)。消费者：① search-indexer 按 version 外部版本写 ES；② cache-evictor 删除 Redis book:detail:ID，并通过 Redis Pub/Sub 频道 cache:evict 广播各实例清除 L1。

### 8.6 Outbox Relay 与 MQ 约定

- Relay（worker，每 200ms）：`SELECT * FROM outbox_event WHERE status=0 ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED`，发送 RocketMQ 成功后批量置 status=1；每日清理 7 天前已发送记录。多实例并行不保证全局顺序，消费者必须按 version 容忍乱序。
- Topic：elibrary-catalog-book、elibrary-lending-loan、elibrary-lending-stock；Tag=eventType；Key=aggregateId；消费组：cg-search-indexer、cg-cache-evictor、cg-redis-projector。
- 消费失败重试 16 次后进入死信队列，死信有告警与人工重投工具。

### 8.7 故障矩阵

| 场景 | 影响 | 保证与处理 |
| --- | --- | --- |
| 应用在 Redis 预占后、落库前崩溃 | 预占泄漏 | ReservationSweeper 30s 内回收 |
| DB 事务失败 | 预占需回滚 | 同步回滚；回滚也失败则 sweeper 兜底 |
| Redis 整体不可用 | 无法预占 | 降级 DB-only（限并发），不超借 |
| Redis 主从切换丢写 | stock 偏高/偏低 | 偏高：DB 条件更新拒绝并重建；偏低：对账修复；任何情况不超借 |
| MQ 不可用 | 事件延迟 | outbox 堆积，业务不受影响，恢复后补发 |
| ES 不可用 | 搜索不可用 | 降级 MySQL 简单查询；恢复后索引器追平 |
| 客户端重复请求 | 重复借阅风险 | 幂等键 + uk\_user\_idem + uk\_user\_book\_active |
| MQ 重复投递 | 重复副作用 | 消费者幂等（版本号/去重键） |
| 归还后 Redis 释放失败 | 缓存偏低 | BookReturned 事件重放 + 对账 |
| MySQL 主库切换 | 事务失败 | 返回 503 可重试，幂等键保证安全 |

## 9. Elasticsearch 设计（检索读模型）

### 9.1 定位与原则

- ES 8.x + IK 中文分词插件；**只存书目检索字段，不存库存与借阅状态**（库存高频变化，写 ES 代价高且最终一致，会造成错误展示）。
- 列表中的“可借份数”不来自 ES：搜索返回 bookId 后，用 Redis `MGET {b:ID}:stock` 批量补充（未预热的回源 MySQL `lending_stock WHERE book_id IN (...)`），total 来自详情缓存/批量查询。
- 搜索结果秒级最终一致；详情与借阅以 MySQL/缓存为准，下架书可能短暂出现在列表中，点击详情或借阅时会得到正确的 404/409。

### 9.2 索引与别名

索引 `books_v1`；别名 `books_read`（查询）与 `books_write`（索引器写入）。映射 dynamic=strict：

```json
{
 "settings": {"number_of_shards": 3, "number_of_replicas": 1, "refresh_interval": "1s"},
 "mappings": {"dynamic": "strict", "properties": {
  "bookId": {"type": "keyword"},
  "isbn": {"type": "keyword"},
  "title": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart",
           "fields": {"keyword": {"type": "keyword", "ignore_above": 256}, "en": {"type": "text", "analyzer": "english"}}},
  "subtitle": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
  "authors": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart", "fields": {"keyword": {"type": "keyword"}}},
  "publisher": {"type": "keyword"},
  "categoryId": {"type": "keyword"},
  "language": {"type": "keyword"},
  "format": {"type": "keyword"},
  "status": {"type": "keyword"},
  "description": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
  "coverUrl": {"type": "keyword", "index": false},
  "publishedDate": {"type": "date", "format": "yyyy-MM-dd"},
  "popularity": {"type": "float"},
  "updatedAt": {"type": "date"}
 }}
}
```

popularity 由每日 worker 任务按近 30 天借阅次数回写（可选，v1 默认 0）。

### 9.3 查询规则

- keyword 非空：`bool.must = multi_match(keyword, fields=[title^5, authors^3, subtitle^2, description], type=best_fields)`；keyword 形如 ISBN（10/13 位数字）时改为对 isbn 做 term 查询。
- `bool.filter`：term(status=PUBLISHED)，以及可选的 categoryId、language、format。
- 排序：relevance=`_score desc, bookId asc`；newest=`publishedDate desc, bookId asc`；popular=`popularity desc, bookId asc`。
- 分页：`search_after`，cursor 是排序值数组的 Base64(JSON)；size 默认 20，最大 50；禁止 from+size 深分页；`_source` 只取列表展示字段。
- 超时 800ms；track\_total\_hits=false。

### 9.4 索引同步（search-indexer 消费者）

- BookPublished/BookUpdated：以 `_id=bookId`、`version_type=external_gte`、`version=book.version` 写入 `books_write`（乱序/重复投递天然幂等）；BookOfflined：删除文档。
- 使用 BulkProcessor：200 条或 1s 触发一次 bulk；失败的条目按指数退避重试，最终进死信。
- **全量重建**（POST /admin/search/reindex，异步）：创建 books\_v{n+1} → 按 book\_id 键集游标每批 1000 条从 MySQL 读取 PUBLISHED 书 → bulk 写入 → 再回放 `updated_at >= 开始时间−5 分钟` 的增量 → 原子切换别名（books\_read 与 books\_write 同一个 `_aliases` 请求）→ 24 小时后删除旧索引。

### 9.5 降级

熔断器（Resilience4j，失败率 50%/20 次，打开 10s）打开或 ES 超时时，列表接口降级为 MySQL：`WHERE status='PUBLISHED' AND (categoryId=?) AND title LIKE 'kw%' ORDER BY book_id` 游标分页（走从库），响应头加 `X-Search-Degraded: true`，仅支持前缀匹配。

## 10. API 设计

### 10.1 通用约定

- 前缀 `/api/v1`；JSON 字段 camelCase；所有 ID 为字符串；时间为 ISO-8601 UTC。
- 请求头：`X-User-Id`（网关透传，借阅/归还/我的借阅必须有，否则 401）、`X-Role`（ADMIN 才能调用 /admin）、`Idempotency-Key`（POST /loans 必须，≤64 字符）、`X-Request-Id`（可选，用于链路追踪并回显）。
- 分页：`size`（默认 20，最大 50）+ `cursor`（不透明字符串）；响应 `{items, nextCursor, hasMore}`。
- 错误：`application/problem+json`，详见 §11。

### 10.2 接口清单

| 方法 | 路径 | 说明 | 成功码 |
| --- | --- | --- | --- |
| GET | /books | 浏览/搜索书籍 | 200 |
| GET | /books/{bookId} | 书籍详情 | 200 |
| POST | /loans | 借阅（热点接口） | 201（重放 200） |
| POST | /loans/{loanId}/return | 归还 | 200 |
| GET | /loans/{loanId} | 借阅详情（仅本人） | 200 |
| GET | /users/me/loans | 我的借阅，status=BORROWED（默认）或 RETURNED | 200 |
| POST | /admin/books | 新建书（DRAFT）并创建库存行 | 201 |
| PUT | /admin/books/{bookId} | 修改书目（body 带 version，不一致 409） | 200 |
| POST | /admin/books/{bookId}/publish 或 /offline | 上架/下架 | 200 |
| PUT | /admin/books/{bookId}/stock | 调整 totalCopies | 200 |
| POST | /admin/search/reindex | 全量重建 ES 索引 | 202 |
| GET | /actuator/health/liveness, /readiness, /prometheus | 运维 | 200 |

### 10.3 请求/响应示例

**GET /books?keyword=分布式&categoryId=12&sort=relevance&size=20**

```json
{"items": [{"bookId": "1856000000000000001", "title": "分布式系统原理", "authors": ["张三"], "coverUrl": "https://cdn.example/c/1.jpg",
  "categoryId": "12", "language": "zh", "format": "EBOOK", "availability": {"available": 3, "total": 10}}],
 "nextCursor": "WzEyLjUsMTg1NjAwMDAwMDAwMDAwMDAwMV0=", "hasMore": true}
```

availability 在补充失败时为 null，不影响列表返回。

**GET /books/{bookId}**：返回 bookId, isbn, title, subtitle, authors, publisher, categoryId, language, description, coverUrl, publishedDate, format, status, availability；响应头 `ETag: W/"<version>"`，`Cache-Control: public, max-age=5`；非 PUBLISHED 或不存在统一返回 404。

**POST /loans**（Header: Idempotency-Key: 7b1c...，X-User-Id: 1001）

```json
{"bookId": "1856000000000000001"}
```

201（重放返回 200 并带头 `Idempotent-Replay: true`），`Location: /api/v1/loans/{loanId}`：

```json
{"loanId": "1856000000000009999", "bookId": "1856000000000000001", "status": "BORROWED",
 "borrowedAt": "2026-11-19T08:00:00.123Z", "dueAt": "2026-12-03T08:00:00.123Z", "returnedAt": null, "returnReason": null}
```

同一 Idempotency-Key 搭配不同 bookId -> 422 IDEMPOTENCY\_KEY\_REUSED。

**POST /loans/{loanId}/return**：无 body；成功返回同样结构的 Loan，status=RETURNED；重复归还同样返回 200。

**GET /users/me/loans?status=BORROWED&size=20**：items 为 Loan + `book: {bookId, title, coverUrl}`（书摘要来自 L1/L2 缓存批量获取，缺失回源 `WHERE book_id IN`）；BORROWED 仅返回 due\_at 大于当前时间的记录。

### 10.4 校验规则

bookId/loanId 必须为数字字符串；size 1-50；sort 仅限三个枚举；管理员接口 title 1-200、isbn 10 或 13 位、authors 1-20 项、totalCopies 0-100000。校验失败统一 400 VALIDATION\_FAILED，并在 `errors[]` 给出字段与原因。

## 11. 错误模型

### 11.1 响应格式（RFC 7807 扩展）

```json
{"type": "https://elibrary.example/errors/book-sold-out", "title": "Book sold out", "status": 409,
 "code": "BOOK_SOLD_OUT", "detail": "所有借阅席位已被占用", "traceId": "4bf92f3577b34da6", "retryable": true}
```

### 11.2 错误码

| code | HTTP | 可重试 | 说明 |
| --- | --- | --- | --- |
| VALIDATION\_FAILED | 400 | 否 | 参数校验失败 |
| MISSING\_IDEMPOTENCY\_KEY | 400 | 否 | 借阅缺少幂等键 |
| UNAUTHENTICATED | 401 | 否 | 缺少 X-User-Id |
| FORBIDDEN | 403 | 否 | 非管理员访问 /admin |
| BOOK\_NOT\_FOUND | 404 | 否 | 书不存在或未上架 |
| LOAN\_NOT\_FOUND | 404 | 否 | 借阅不存在或不属于当前用户 |
| BOOK\_SOLD\_OUT | 409 | 是 | 席位已满，稍后重试 |
| BOOK\_NOT\_BORROWABLE | 409 | 否 | 书已下架 |
| ALREADY\_BORROWED | 409 | 否 | 当前已借该书 |
| LOAN\_LIMIT\_EXCEEDED | 409 | 否 | 在借数量达到上限 5 |
| REQUEST\_IN\_PROGRESS | 409 | 是 | 同一幂等键请求仍在处理，1s 后重试 |
| DUPLICATE\_ISBN | 409 | 否 | ISBN 已存在 |
| STOCK\_BELOW\_BORROWED | 409 | 否 | 调整后份数小于已借出数 |
| VERSION\_CONFLICT | 409 | 否 | 乐观锁冲突 |
| IDEMPOTENCY\_KEY\_REUSED | 422 | 否 | 幂等键与不同请求复用 |
| RATE\_LIMITED | 429 | 是 | 限流，带 Retry-After |
| INTERNAL\_ERROR | 500 | 否 | 未预期错误，附 traceId |
| SERVICE\_UNAVAILABLE | 503 | 是 | 依赖故障或过载，带 Retry-After |

### 11.3 实现规则

领域异常 -> application 抛 BizException(ErrorCode) -> @RestControllerAdvice 统一映射；不向外泄露堆栈、SQL、内部类名；4xx 记 INFO（BOOK\_SOLD\_OUT 不打日志只计数），5xx 记 ERROR 并带 traceId；客户端可据 retryable 与 Retry-After 退避重试。

## 12. 高可用与降级

### 12.1 部署拓扑

| 组件 | 配置 |
| --- | --- |
| 网关 | 多实例，JWT 校验、用户/IP 限流、WAF；仅网关可访问应用（NetworkPolicy/mTLS），防伪造 X-User-Id |
| 应用 | api ≥3 Pod、worker ≥2 Pod，跨 2 个 AZ，PodAntiAffinity + PDB(minAvailable=2)，HPA 按 CPU 与 RPS，Tomcat 开启虚拟线程；Hikari 每实例 40 连接 |
| MySQL 8.0 | MGR（3 节点）或 1 主 2 从半同步（rpl\_semi\_sync，等待至少 1 从），自动故障切换；备份每日全量 + binlog |
| Redis 7 | Cluster 3 主 3 从，AOF everysec，跨 AZ，故障自动切换；内存策略 noeviction（预占键不可被淘汰），缓存键放独立实例或限定 TTL |
| RocketMQ 5 | 3 Broker 主从（DLedger/Controller 模式），Topic 副本 ≥2 |
| Elasticsearch 8 | 3 节点（均为 master-eligible），3 分片 1 副本，allocation awareness 跨 AZ |

### 12.2 降级与保护

| 依赖/场景 | 检测 | 行为 |
| --- | --- | --- |
| Redis 不可用 | Resilience4j 熔断（失败率 50%/20 次，打开 10s，半开探测 5 次） | **DB-only 借阅路径**：跳过 Redis 预占，直接 persistBorrow；用 Semaphore(200) 限制实例并发，超限 503 + Retry-After；熔断按 Redis 槽组独立判定，恢复后按 §12.5 换纪元（epoch+1），旧 Redis 键整体作废，从 MySQL 懒重建 |
| ES 不可用 | 熔断或 800ms 超时 | 列表降级 MySQL 前缀查询 |
| MQ 不可用 | 发送失败 | outbox 堆积，业务不受影响；堆积量告警 |
| MySQL 主库故障 | 连接/超时 | 借还返回 503；浏览走缓存与从库（可能轻微过期） |
| 瞬时洪峰 | 网关限流 + Sentinel 并发隔离 + 本地售罄标记 | 快速失败 429/409，保护 Redis 与 MySQL |
| 详情缓存击穿 | 同一 key 并发回源 | 单飞（Caffeine 加载锁）+ Redis 互斥锁，空值缓存 60s，TTL 抖动 ±20% |

### 12.3 容量估算（示例，需压测校准）

单热点书 20k RPS 入口：约 95% 以上被本地售罄标记或 Redis 拒绝，走 Redis 的请求单脚本 <0.3ms，Redis 单分片可承受 10 万级 ops/s；落库只发生在成功者身上，设库存周转 2k/s，则 MySQL 单行条件更新（事务 <5ms）可承受。api 单实例拒绝路径约 5k RPS，故 ≥4 实例，按压测结果由 HPA 放大。

### 12.4 发布与运维

滚动发布 + 优雅停机（readiness 先置为 down，等待在途请求完成最多 30s）；数据库迁移遵循 expand/contract（先加后删，向后兼容）；限流阈值、借期天数、配额上限等通过配置中心动态下发；基线安全：参数化 SQL、日志脱敏、管理员操作审计日志。

### 12.5 Redis 故障闭环：挂了、丢数据、主从切换，数据都不会不一致

**结论**：Redis 里的一切都能从 MySQL 重建，而 MySQL 的条件 UPDATE 与唯一索引随时拒绝违规写入。所以 Redis 出任何问题只会造成两种后果：误拒（库存偏低，少借出）或被 DB 拒绝（库存偏高，多一次无效尝试），不会超借、不会重复借、不会丢借阅。恢复时不在旧键上修补，而是换纪元整体作废再从 MySQL 重建。

&#91;embedded content: Redis 槽组状态机 · 3 个状态 4 条转移\]

**机制 1：纪元 epoch（一键作废全部旧预占状态）**

```sql
CREATE TABLE sys_epoch (id TINYINT NOT NULL, epoch BIGINT NOT NULL, updated_at DATETIME(3) NOT NULL, PRIMARY KEY (id));
INSERT INTO sys_epoch VALUES (1, 1, NOW(3));
-- 换纪元（CAS，多实例同时触发只有一个成功）
UPDATE sys_epoch SET epoch = epoch + 1, updated_at = ? WHERE id = 1 AND epoch = ?;
```

- 所有预占键带纪元（§7.1）；应用每 1 秒读取 sys\_epoch 并缓存，也接收配置中心推送。
- 换纪元后：旧键不再被读写；新纪元键不存在 -> NOT\_WARM -> 按 §7.4 从 MySQL 懒预热；各实例清空本地售罄标记。
- 在途请求：旧纪元下已预占但未提交的借阅仍会落库（以 DB 为准）；新纪元的预热值可能比真实值略高，会被 DB 条件 UPDATE 拒绝并触发该书键重建，StockReconciler 随后收敛。

**机制 2：槽组熔断 + DB-only**：Resilience4j 熔断器按 Redis 槽组（slot / 1024，共 16 组）独立统计；某组打开时，只有落在该组的书走 DB-only 路径（跳过预占，直接 persistBorrow；每实例 Semaphore(200)，单书再限 50 并发，超限 503 + Retry-After），其他书不受影响。DB-only 下的正确性由 §6 的条件 UPDATE 与唯一索引保证。

**机制 3：canary 探测静默丢数据**：主从切换、节点重启丢失未持久化数据时 Redis 不报错，熔断感知不到。每个 Redis 主节点放一个 canary 键（启动时用 SlotHash 暴力搜索一个落入该节点槽位的 tag，值为当前 epoch）；worker 每 5 秒 MGET 全部 canary，任一缺失或不等于当前 epoch，立即 CAS 换纪元并重写 canary。

**机制 4：结果不确定时不回滚**（§8.1 第 6 步）：只有确定性失败才立刻释放预占；超时、断连、提交状态未知时保留预占，由 sweeper 用主库查询裁决（存在则仅确认；确认不存在才释放）。MySQL 自身故障期间 sweeper 查询报错则整轮跳过，宁可暂时多占，不可误放。

**机制 5：关键读强制主库**：sweeper、幂等重放、对账、“我的借阅”读己之写，一律读主库，避免复制延迟把已提交误判为不存在。

**机制 6：预热风暴保护**：换纪元后大量书同时 NOT\_WARM；预热受 §7.4 互斥锁与每实例预热并发 ≤ 50 约束，worker 在换纪元后按近 7 天借阅量 Top 1000 主动预热热门书，其余懒加载。

**自动恢复流程（Runbook）**

1. 检测：熔断打开，或 canary 缺失（直接换纪元）。
2. 止血：DB-only 限并发，告警 degraded\_mode\_gauge=1。
3. 探测：半开探测 5 次成功 -> RECOVERING。
4. 换纪元：CAS epoch+1，重写 canary，各实例清空本地售罄标记。
5. 重建：懒预热 + 热门书预热；StockReconciler 连续两轮无差异 -> 回到 NORMAL，告警解除。
6. 事后核验：对账任务全量比较 DB 与 Redis，差异计入 stock\_reconcile\_fix\_total，应为 0。

### 12.6 异常闭环总表（检测 -> 止血 -> 自动修复 -> 验证）

| 异常 | 检测 | 止血 | 自动修复 | 验证 / 告警 |
| --- | --- | --- | --- | --- |
| 重复请求、网络重试 | Idempotency-Key + uk\_user\_idem | 返回原 loan | 无需修复 | borrow\_requests{replay} |
| 预占后进程崩溃 | pending 超时 30s | 库存暂占不超过 30s | sweeper 查主库：存在则确认，不存在则释放 | sweeper\_released\_total |
| DB 提交结果不确定 | 超时、断连类异常 | 不回滚预占，返回 503 | sweeper 裁决；客户端同幂等键重试得到原结果 | reservation\_pending\_gauge |
| DB 确定性失败 | 条件 UPDATE 0 行、唯一键冲突 | 立即回滚预占 | 售罄时使该书键重建 | borrow\_requests{sold\_out} |
| Redis 某槽组不可用 | 槽组熔断 | 该组的书 DB-only 并限并发 | 恢复后换纪元 | degraded\_mode\_gauge |
| Redis 静默丢数据 | canary 缺失 | 立即换纪元 | 懒预热 + 对账 | epoch\_bump\_total |
| Redis 库存偏高 | DB 条件 UPDATE 影响 0 行 | 返回售罄，不超借 | 使该书键失效并重建 | stock\_reconcile\_fix\_total |
| Redis 库存偏低 | 对账连续两轮有差异 | 少借出（保守方向） | StockReconciler 覆盖 | 同上 |
| 归还时 Redis 释放失败 | 调用异常 | DB 已正确，仅缓存偏低 | BookReturned 事件重放 + 对账 | mq\_consumer\_lag |
| 配额漂移 | 对账差异 | DB 配额更新为终裁 | QuotaReconciler 覆盖 | quota\_reconcile\_fix\_total |
| MySQL 主库故障或切换 | 连接超时、只读错误 | 借还 503 + Retry-After；sweeper 不释放预占 | 恢复后继续裁决，幂等键保证重试安全 | hikari 指标、5xx 比例 |
| MQ 不可用或积压 | outbox\_lag | 业务不受影响 | relay 补发，消费者幂等 | outbox\_lag\_seconds |
| 事件重复或乱序 | version / 去重键 | 丢弃旧版本 | 无需修复 | dlq\_size |
| ES 不可用或落后 | 熔断 / es\_index\_lag | 列表降级 MySQL | 恢复后索引器追平，必要时 reindex | es\_degraded\_total |
| 到期任务延迟 | 查询过滤 due\_at | 用户看不到已到期的书 | 任务追赶 | 到期积压量 |
| 实例被杀或滚动发布 | readiness | 优雅停机 30s | sweeper 回收残留预占 | 同“预占后进程崩溃” |

## 13. 可观测性、配置与测试

### 13.1 指标与告警（Micrometer → Prometheus；日志 JSON；追踪 OpenTelemetry，MQ 消息属性透传 traceparent）

| 指标 | 告警条件 |
| --- | --- |
| borrow\_requests\_total{result=ok/sold\_out/already/limit/replay/error}、borrow\_latency | error 占比 >1% 持续 3 分钟；成功路径 P99 >300ms |
| soldout\_local\_hit\_total、redis\_reserve\_latency | 仅看板 |
| reservation\_pending\_gauge、sweeper\_released\_total | sweeper 持续有回收（说明存在崩溃或回滚失败） |
| stock\_reconcile\_fix\_total、quota\_reconcile\_fix\_total | 持续 >0（说明 Redis 与 MySQL 在漂移） |
| outbox\_backlog\_gauge、outbox\_lag\_seconds | lag >30s |
| mq\_consumer\_lag、dlq\_size | lag 持续增长；dlq\_size >0 |
| es\_index\_lag\_seconds、es\_degraded\_total | lag >60s |
| degraded\_mode\_gauge（DB-only 是否开启）、circuit\_state | 开启即告警 |
| hikari\_active\_connections、mysql\_deadlock\_retry\_total | 使用率 >80% |

日志字段：traceId、userId、bookId、loanId、result；不记录令牌与个人隐私。

### 13.2 配置项（application.yml，支持配置中心动态刷新者标注 \*）

| key | 默认 |
| --- | --- |
| app.role | all（api / worker / all） |
| elibrary.lending.max-active-loans-per-user \* | 5 |
| elibrary.lending.loan-days \* | 14 |
| elibrary.lending.reservation.pending-ttl-ms | 30000 |
| elibrary.lending.reservation.idem-ttl-seconds | 86400 |
| elibrary.lending.reservation.quota-ttl-seconds | 604800 |
| elibrary.lending.local-soldout.ttl-ms / max-size | 500 / 100000 |
| elibrary.lending.db-only.max-concurrency \* | 200 |
| elibrary.redis.command-timeout-ms | 100 |
| elibrary.expire-job.interval-seconds / batch-size | 30 / 500 |
| elibrary.outbox.relay.interval-ms / batch-size | 200 / 200 |
| elibrary.search.timeout-ms / max-page-size | 800 / 50 |
| elibrary.cache.detail-ttl-seconds / null-ttl-seconds | 600 / 60 |

### 13.3 测试策略与验收红线

- **单元测试**：Loan/LendingStock/UserLendingAccount 的不变式与状态机、游标编解码、BorrowPolicy。
- **集成测试**（Testcontainers：MySQL 8、Redis 7、ES 8 + IK；MQ 在大多数测试中用内存实现替代 port，另设 1 个 RocketMQ 冒烟测试）。
- **并发正确性测试（红线，必须全部通过）**
  - T1：库存 100，2000 个用户并发借同一本书 -> 成功恰好 100，其余 BOOK\_SOLD\_OUT；DB available=0 且在借记录=100。
  - T2：同一 Idempotency-Key 并发 50 次 -> loan 只有 1 条，所有响应指向同一 loanId。
  - T3：同一用户并发借 10 本不同的书 -> 成功恰好 5。
  - T4：同一用户用不同幂等键并发借同一本书 20 次 -> 成功 1。
  - T5：借/还随机混合 10 万次后，DB 不变式成立：available = total − 在借数；account.active\_count = 该用户在借数；对账后 Redis = DB。
  - T6：故障注入：Lua 预占后不落库 -> sweeper 回收后库存恢复。
  - T7：Redis 不可用 -> DB-only 路径仍不超借；恢复后 Redis 键被重建。
  - T8：同一 loan 并发归还 100 次 -> 库存只 +1。
  - T9：ES 事件乱序/重复 -> 最终文档版本为最大 version。
  - T10：ArchUnit 分层规则（§5）全部通过。
- **性能测试**：k6 脚本 perf/borrow-hot.js，阶梯加压至 20k RPS，对照 §1.3 验收。
- **契约**：springdoc 生成 openapi.yaml 入库，接口变更必须同步。

### 13.4 故障注入与闭环测试（红线补充，M7 验收必须全部通过）

| 编号 | 注入 | 预期 |
| --- | --- | --- |
| T11 | 借阅压测中停掉 Redis 60 秒再恢复 | 全程成功数 ≤ 库存；恢复后 epoch+1；DB 不变式成立；Redis 与 DB 对账差异为 0 |
| T12 | 压测中对 Redis 执行 FLUSHALL（模拟主从切换丢数据） | canary 在 5 秒内触发换纪元；成功数 ≤ 库存 |
| T13 | DB 事务提交后丢弃响应（模拟提交超时） | 预占不被误释放；sweeper 确认后 Redis 与 DB 一致；同幂等键重试返回原 loan |
| T14 | 停 MySQL 主库 60 秒 | 借还返回 503；sweeper 不释放任何预占；恢复后收敛且无超借 |
| T15 | 人为把 Redis 库存改大 50 | 并发借阅仍不超借；被 DB 拒绝后该书键自动重建 |
| T16 | 换纪元的同时并发借还 10 万次 | DB 不变式成立；无重复借阅 |

## 14. 实施里程碑（按顺序执行，每步通过验收才可继续）

### 14.0 澄清约定（优先级高于前文的模糊处）

- C1：lending\_stock 行缺失按“0 份”处理；PUT /admin/books/{id}/stock 为 upsert（`INSERT ... ON DUPLICATE KEY UPDATE`）。POST /admin/books 带 totalCopies 时，由 interfaces 层的管理编排器先调 catalog，再调 lending 的 StockAdminFacade（两个独立事务，后者失败则库存为 0，管理员可重试调整）。
- C2：幂等重放返回 200 + 头 Idempotent-Replay: true；首次成功返回 201。
- C3：GET /books/{id} 对非 PUBLISHED 返回 404；“我的借阅”里的书摘要读取不过滤状态（已下架书仍可显示）。
- C4：API 层 ID 为字符串，内部为 long；userId 来自 X-User-Id，非数字返回 401。
- C5：Snowflake workerId 由 K8s StatefulSet 序号或配置分配；时钟回拨超过 5ms 拒绝生成并告警。
- C6：提供 seed 脚本：100 本书，库存 1-50，另设 1 本 hot 书库存 100，用于演示与压测。
- C7：hash tag 中的 ID 一律使用十进制字符串。

### 14.1 里程碑

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| M0 脚手架 | Maven 工程、Spring Boot、Flyway V1（§6.2 全部表）、docker-compose（MySQL、Redis、ES+IK、RocketMQ）、shared 内核（Snowflake、ClockProvider、BizException/ErrorCode、ProblemDetail advice、TraceFilter、UserContext、Cursor）、ArchUnit | 应用可启动，/actuator/health 为 UP，T10 通过 |
| M1 catalog | Book 聚合与仓储、admin 建/改/上下架 API、GET /books/{id}（L1+L2 缓存、防穿透/击穿/雪崩）、outbox 写入 | 单元 + 集成测试通过；缓存命中不访问 MySQL |
| M2 search | ES 索引脚本、indexer 消费者（external\_gte）、GET /books（搜索+Redis MGET 补库存+MySQL 降级）、reindex | 乱序事件测试 T9；ES 关闭时列表降级可用 |
| M3 lending 领域与持久化 | Loan/LendingStock/UserLendingAccount/BorrowPolicy 及单元测试；MyBatis 仓储；§6.3 全部 SQL；persistBorrow/persistReturn 事务；库存管理 | 单元测试覆盖不变式；纯 DB 并发借阅不超借 |
| M4 Redis 预占借阅 | 全部 Lua 与 RedisReservationAdapter、预热、本地售罄标记、幂等、BorrowService 完整流程 | T1、T2、T3、T4 通过 |
| M5 归还与查询 | ReturnService、ExpireLoansService、GET /loans/{id}、GET /users/me/loans | T5（先不含对账）、T8 通过 |
| M6 事件与自愈 | outbox relay、RocketMQ 生产/消费（indexer、cache-evictor、redis-projector）、ReservationSweeper、Stock/QuotaReconciler | T5（含对账）、T6 通过 |
| M7 降级与高可用 | Resilience4j 熔断、DB-only 路径、Sentinel 限流、优雅停机、动态配置 | T7 通过；Redis/ES 故障演练通过 |
| M8 收尾 | 指标与告警规则、k6 压测与结果记录、OpenAPI、README、k8s/Helm 样例 | 压测达到 §1.3 目标或记录瓶颈与结论 |

## 15. 实现禁止项（防跑偏守则）

1. 禁止把 Redis 或 ES 当作库存/借阅的事实依据；库存与借阅的最终裁决只能是 MySQL 条件 UPDATE 与唯一索引。
2. 禁止“先 SELECT 再 UPDATE”判断库存或配额；必须条件 UPDATE 并检查影响行数。
3. 禁止在 @Transactional 内调用 Redis/MQ/ES/HTTP，禁止事务内 sleep；@Transactional 只出现在 application 层。
4. 禁止在 Controller 中写业务逻辑或事务；禁止 domain 层依赖任何框架（含 Lombok 之外的注解库）。
5. 禁止跨上下文直接访问对方的表、domain、infrastructure；只能通过 facade 或事件。
6. 禁止 offset 深分页；禁止出现不命中 §6 索引的查询；Redis 禁用 KEYS/FLUSHALL，需扫描只可用 SCAN 且仅限 worker。
7. 禁止把预占 Lua 拆成多条 Redis 命令（必须原子）；回滚预占只能用 stock\_release\_failed（无条件回补会造成超发）。
8. 禁止修改 §1.4 固定决策、错误码、API 字段、表结构语义（尤其 uk\_user\_book\_active、uk\_user\_idem）；需要变更必须先更新本文档并说明原因。
9. 禁止引入未列出的中间件或框架（如 Kafka、MongoDB、JPA）。
10. 禁止吞异常、返回堆栈、在日志输出令牌或隐私信息；MQ 消费者必须幂等，且不得依赖消息顺序。
11. 时间一律通过 ClockProvider 获取，禁止直接 Instant.now()；ID 一律通过 IdGenerator。
12. 每个里程碑必须附带测试，验收未过不得进入下一个里程碑；遇到文档未覆盖或矛盾之处，先列出问题并按 §2 假设取最保守方案，不得自行扩大范围。

**补充禁止项（闭环相关）**：13. 结果不确定（超时、断连、提交状态未知）时禁止回滚预占；14. sweeper、对账、幂等重放禁止读从库，DB 查询报错时禁止释放预占；15. Redis 故障恢复后禁止在旧键上原地修补，必须换纪元；16. Redis 键名必须通过 RedisKeys 统一构造（带 epoch），禁止拼字符串。

### 附录 A：交给大模型的每里程碑提示词模板

“你是资深 Java 后端工程师。唯一依据是《E-Library 后端设计与实施文档》。现在实现里程碑 Mx：<粘贴该里程碑行与相关章节>。规则：① 不得违反 §1.4 与 §15；② 先写测试再写实现；③ 输出每个文件的完整路径与完整代码；④ 完成后逐条对照验收标准自检；⑤ 对文档未覆盖或矛盾之处，单独列出并采用最保守的方案，不要擅自新增功能。”

## 16. README 提纲、设计取舍与扩展点

### 16.1 README 提纲（交付物）

1. 概览与架构图；2. 快速开始（docker compose up、启动应用、导入 seed、curl 示例）；3. API 摘要与错误码；4. 设计要点（DDD 划分、借阅防超借三层、一致性与自愈、降级）；5. 关键取舍（见下表）；6. 假设清单（§2 与 §14.0）；7. 如何运行测试与压测；8. 已知局限与演进路线。

### 16.2 关键取舍

| 决策 | 选择 | 理由与代价 |
| --- | --- | --- |
| 架构形态 | 模块化单体 + 六边形 | 简单可运维；代价是共享部署，靠 ArchUnit 守边界，后续可按上下文拆分 |
| 防超借 | Redis Lua 预占 + MySQL 条件 UPDATE 终裁 | 拒绝请求不碰数据库，Redis 丢数据也不超借；代价是两层状态需对账 |
| 落库方式 | 同步落库 | 成功请求数 ≤ 库存且用户需即时结果；MQ 异步落库适合库存巨大且落库成为瓶颈时（扩展点） |
| 库存是否进 ES | 否 | 高频变更与最终一致不适合；列表用 Redis 批量补充 |
| 事件投递 | Transactional Outbox | 不依赖分布式事务；代价是需 relay 与消费者幂等 |
| 分页 | 游标 | 深分页稳定高效；代价是不支持跳页 |
| 到期处理 | 主动任务 + 查询时过滤 | 任务延迟不影响用户看到的正确性 |
| 一致性 | MySQL 强一致，Redis/ES 最终一致 | 以正确性为先，可用性与性能由缓存层承担 |

### 16.3 扩展点（v1 不实现，接口已留位）

- 续借、预约排队（Redis ZSET + 归还事件触发通知）。
- 库存分槽：lending\_stock\_slot(book\_id, slot\_id, available\_copies) 与多 tag 的 Redis stock 键，用于单书热点超过单节点上限的场景。
- ASYNC 落库模式：预占成功后写 MQ 返回 202，消费者落库，前端轮询 /loans/{id}；通过 LoanPersistStrategy 端口切换。
- 用 Canal/CDC 替代 outbox relay；内容访问令牌（借阅期内签发）；热度推荐；ShardingSphere 分库分表落地；自适应限流。
