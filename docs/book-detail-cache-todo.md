# 书籍详情缓存改造待办

> 状态：待实施  
> 记录日期：2026-11-21  
> 作用范围：`GET /api/v1/books/{bookId}`

## 1. 当前结论

详情缓存采用“Catalog 元数据缓存、Lending 库存实时查询”的边界。应用查询层按
`bookId` 组装两部分数据，库存继续以 MySQL 为权威来源。

这项改造适合作为容量优化和读模型解耦。当前实测中，详情接口在 10 万书目、并发 20
的受限容器环境下达到 1,038.3 RPS，p50 为 6.16 ms，p95 为 74.25 ms；书名前缀查询是
已经确认的主要性能瓶颈。因此缓存改造采用分阶段交付，并用命中率、数据库 QPS 和同环境
前后压测决定是否引入 Redis。

参考资料：

- [当前架构](architecture.md)
- [缓存前性能基准](performance/pre-cache-container-baseline-2026-11-21.md)
- [500 万书目查询基准](performance/pre-cache-container-5m-pagination-2026-11-21.md)

## 2. 当前实现事实

- `CatalogService.get` 通过 `CatalogMapper.findById` 查询详情。
- `findById` 使用 `book LEFT JOIN lending_stock` 一次返回元数据与库存。
- `LendingMapper.findStock` 已提供按 `book_id` 主键读取权威库存的 SQL。
- Catalog 写操作直接使用 MyBatis 条件更新和乐观锁，并在同一事务写入 Outbox。
- 现有 Catalog 事件包括 `BookCreated`、`BookUpdated`、`BookPublished` 和
  `BookOfflined`，事件信封已经包含 `aggregateId` 与 `aggregateVersion`。
- 当前工程尚未引入 Caffeine、Redis 客户端、Redis 容器和缓存配置。
- 当前业务错误协议使用 HTTP 200 与 `BOOK_NOT_FOUND` 业务码。

## 3. 目标结构

```text
BookController
  -> BookDetailQueryService.get(bookId)
       -> BookMetaReader.findById(bookId)
            -> L1 Caffeine
            -> L2 Redis（按验证结果启用）
            -> Catalog MySQL
       -> StockReader.findByBookId(bookId)
            -> Lending MySQL
       -> BookDetailView
```

约束：

- `BookMetaView` 包含 API 所需的全部 Catalog 字段，包括 `status` 和 `version`。
- `StockView` 包含 `totalCopies`、`availableCopies`、`stockVersion` 和查询时间。
- Reader 之间只使用 `bookId` 关联，不互相传递 Catalog/Lending 持久化对象。
- 内部继续使用 `long bookId`，Redis key 使用其十进制字符串形式。
- 公开详情在组装层检查 `PUBLISHED` 状态并维持现有错误协议。
- 列表查询、借阅事务中的状态锁定和管理端写路径继续使用现有数据库实现。

## 4. 分阶段待办

### 阶段 A：拆分详情读模型

- [ ] 新增只读 `BookMetaView` 与 `BookMetaReader` 接口。
- [ ] 新增 Catalog MySQL Reader，查询元数据时不联结 `lending_stock`。
- [ ] 新增 `StockReader` 接口，由 Lending 的 `findStock` 查询实现。
- [ ] 新增 `BookDetailQueryService`，按 `bookId` 组装详情。
- [ ] 保持公开可见性、作者 JSON 转换、库存 `asOf/source` 和响应字段兼容。
- [ ] 保持 `browse` 当前的列表查询行为。
- [ ] 保持创建、更新、上下架和库存调整的写事务语义。
- [ ] 为跨上下文依赖方向增加 ArchUnit 约束。

### 阶段 B：加入 Caffeine L1

- [ ] 引入 Caffeine 依赖并以 Reader 装饰器封装缓存逻辑。
- [ ] 增加 `app.cache.enabled`、容量和 TTL 的类型化配置。
- [ ] 正值采用 20 秒绝对过期，空值采用 10 秒绝对过期。
- [ ] 使用按值 `Expiry` 实现正值和空值的不同 TTL。
- [ ] 使用 `Optional<BookMetaView>` 或显式结果类型缓存不存在结果。
- [ ] 评估 `description` 等变长字段的实际内存占用，确定使用
  `maximumSize` 或 `maximumWeight`。
- [ ] 开启 Caffeine 统计并接入 Micrometer。
- [ ] `cache.enabled=false` 时直接委托 MySQL Reader。

### 阶段 C：按数据决定是否加入 Redis L2

- [ ] 记录 L1 命中率、元数据 DB QPS、热点分布和实例数量。
- [ ] 确认 L2 收益后引入 Redis 客户端与独立缓存配置。
- [ ] 在 Compose 和环境变量示例中增加 Redis。
- [ ] 使用统一 key 构造器生成 `elib:v1:book:{id}`。
- [ ] 使用显式缓存 envelope 区分“未命中”“存在”“书籍不存在”。
- [ ] 正值 TTL 采用 10 分钟并加入正负 20% 抖动。
- [ ] 空值 Redis TTL 采用 60 秒。
- [ ] 设置连接、命令和连接池等待超时，并记录超时指标。
- [ ] Redis 读取失败时降级到 Catalog MySQL。
- [ ] 增加短路保护，避免 Redis 故障期间每个冷 key 都等待完整超时。
- [ ] 设计并实现基于 `version` 的回填栅栏，覆盖旧值晚于删除写回的竞态。

### 阶段 D：提交后失效与 Outbox 补偿

- [ ] 本地提交成功后同步删除 Redis 元数据并清理本实例 L1。
- [ ] Redis 删除与 L1 清理分别执行，Redis 异常不能跳过 L1 清理。
- [ ] 复用现有 `BookCreated`、`BookUpdated`、`BookPublished`、
  `BookOfflined` 事件。
- [ ] 缓存失效逻辑只读取事件的 `aggregateId` 和 `aggregateVersion`。
- [ ] 增加 MQ 缓存失效消费者、消费重试和失败指标。
- [ ] 保持删除操作幂等，允许事件重复和乱序投递。
- [ ] `BookBorrowed`、`BookReturned`、`StockResized` 不触发元数据失效。
- [ ] 明确多实例一致性契约：写入实例提交后立即可见，其他实例 L1 最长
  保留 20 秒旧值。
- [ ] 若产品要求集群立即可见，增加 L1 广播失效机制并重新定义验收标准。

## 5. 一致性风险

### 5.1 删除与回填竞态

单纯在提交后删除缓存存在以下交错：

```text
请求 R 从 MySQL 读到元数据 v1
写事务 W 提交元数据 v2
W 删除 Redis 和本地 L1
R 将较早读到的 v1 写回 Redis/L1
```

仅依赖删除时，Redis 中的 v1 最长可能保留到正值 TTL 到期。实现 L2 前必须选择并记录
以下一致性契约之一：

- 使用事件 `version` 建立 Redis 回填栅栏，并防止旧版本覆盖新版本。
- 接受 TTL 界定的最终一致性，并将 Redis TTL 调整为业务允许的陈旧窗口。

### 5.2 多实例 L1

本地提交后处理器只能立即清理当前实例。中央 Redis 删除可以阻止后续 L2 命中旧值，其他
实例已持有的 L1 仍按 20 秒绝对 TTL 到期。管理端“改完立即可见”的表述必须注明作用范围。

### 5.3 内存预算

书目包含 `TEXT description`、作者 JSON 和封面 URL。10,000 条缓存的内存占用随内容长度
变化，需要在当前容器内存限制下测量对象大小、GC 和淘汰情况后确定容量。

## 6. 测试待办

- [ ] 100 个并发请求读取同一个冷 key：元数据 DAO 查询 1 次，库存 DAO 查询 100 次。
- [ ] 同一个不存在 ID 连续读取：第二次不查询元数据 DB，库存 DAO 始终不执行。
- [ ] 创建书籍后清除同 ID 的负缓存。
- [ ] 修改标题后再次读取到新标题。
- [ ] 发布和下架后公开可见性按现有协议变化。
- [ ] Redis 读取、写入和删除分别抛异常时，详情仍从 MySQL 正确返回。
- [ ] Redis 删除失败时，本实例 L1 仍被清理。
- [ ] 借书、还书和调整库存后，详情中的库存实时变化，元数据缓存保持命中。
- [ ] 构造“旧版本 DB 读取 -> 新版本提交与删除 -> 旧版本回填”并验证版本栅栏。
- [ ] 验证重复、乱序 Catalog 事件只造成额外未命中，不返回旧版本。
- [ ] 双实例测试确认远端 L1 的最大陈旧窗口符合契约。
- [ ] `cache.enabled=false` 时每次元数据读取都访问数据库。
- [ ] 使用可控 Ticker 测试 10 秒、20 秒过期，无需真实等待。

## 7. 性能与可观测性验收

- [ ] 记录 L1/L2 命中、未命中、负缓存命中、加载成功和加载失败次数。
- [ ] 记录 Redis 延迟、超时、降级、删除失败和版本栅栏拒绝回填次数。
- [ ] 记录元数据与库存 SQL 的调用次数及延迟。
- [ ] 在相同容器、数据集、并发和请求数下分别测试 L1 命中、L2 命中和冷未命中。
- [ ] 对比缓存前详情基准，确认命中路径收益和冷未命中路径开销。
- [ ] 执行 Redis 故障压测，确认 MySQL 回源量保持在连接池和数据库容量预算内。
- [ ] 将后测原始结果与报告保存到 `docs/performance/`。
- [ ] 单独推进书名前缀 SQL/索引优化；详情缓存不承担该查询问题的解决目标。

## 8. 完成标准

- 元数据和库存的所有权边界在代码与架构测试中可验证。
- 库存每次从 MySQL 读取，借还后的下一次详情查询反映最新已提交库存。
- 同 JVM 的同 key 冷加载只执行一次元数据回源。
- 空值 TTL、正值 TTL、Redis TTL 抖动和缓存开关均有自动化测试。
- Redis 故障不会改变详情正确性，降级行为具有日志、指标和容量验证。
- 缓存失效覆盖创建、更新、发布和下架，且本地 L1 清理不依赖 Redis 成功。
- 旧值回填竞态、多实例陈旧窗口和管理端可见性均有明确契约及测试。
- README 使用“缓存边界遵循 Catalog/Lending 数据所有权边界”的表述。

