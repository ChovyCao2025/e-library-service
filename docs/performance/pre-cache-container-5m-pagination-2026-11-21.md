# Pre-cache five-million-row pagination baseline

## Record status

- Stage: before adding application caching
- Completion: 2026-11-21 00:21:13.440 +08:00
- Time zone: Asia/Shanghai (UTC+08:00)
- Deployment: constrained Docker containers
- Application limit: 2 CPU, 1 GiB memory, no additional swap
- MySQL limit: 2 CPU, 2 GiB memory, no additional swap
- MySQL: 8.4.7, 256 MiB InnoDB buffer pool
- Base URL: `http://127.0.0.1:18080`

The full host, image, JAR, and container specifications are recorded in
`docs/performance/pre-cache-container-baseline-2026-11-21.md`.

## Dataset

| Item | Value |
| --- | ---: |
| Total books | 5,000,101 |
| Generated benchmark books | 5,000,000 |
| Published | 4,500,101 |
| Draft | 250,000 |
| Offline | 250,000 |
| Lending stock rows | 5,000,101 |
| Minimum book ID | 1,000,001 |
| Maximum book ID | 7,999,999 |
| Table data | 1,415.91 MiB |
| Index data | 662.58 MiB |

The additional 4.9 million books and stock rows were inserted in 100,000-row batches. Loading and
analyzing the tables took 170.96 seconds while the MySQL container remained limited to 2 CPU and
2 GiB memory.

## HTTP method

- Pagination mechanism: `afterBookId` keyset cursor
- Eight depth and filter scenarios
- 1,000 measured requests per scenario, 8,000 total
- Concurrency: 20
- Twenty sequential warm-up requests per scenario
- Page size: 20
- Every scenario was probed for `code: 0000` and exactly 20 items before load
- All 8,000 measured responses returned HTTP 200

## Keyset pagination results

| Scenario | Cursor position | RPS | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| First page | 0% | 476.4 | 10.09 ms | 97.69 ms | 103.23 ms | 175.88 ms |
| Deep cursor | 25% | 791.4 | 6.78 ms | 78.44 ms | 81.38 ms | 87.73 ms |
| Deep cursor | 50% | 753.1 | 7.11 ms | 78.03 ms | 80.98 ms | 85.41 ms |
| Deep cursor | 75% | 882.2 | 6.23 ms | 77.98 ms | 78.77 ms | 81.80 ms |
| Deep cursor | 90% | 850.6 | 5.83 ms | 77.81 ms | 80.15 ms | 174.63 ms |
| Deep cursor | 99% | 911.1 | 5.92 ms | 76.99 ms | 82.20 ms | 93.59 ms |
| Deep cursor | 99.9% | 962.7 | 5.73 ms | 76.39 ms | 79.09 ms | 80.83 ms |
| Deep cursor plus filters | 99% | 947.3 | 5.83 ms | 75.05 ms | 77.88 ms | 79.24 ms |

The measured p95 and p99 do not increase with cursor depth. The endpoint performs an indexed range
seek from `afterBookId`, so it does not discard all preceding rows.

## Why the keyset results are fast

The result is plausible for this specific query shape and environment:

1. The current application has no Spring Cache, Caffeine, Redis read cache, or custom cache manager.
   The only cache-related web setting on the catalog path is `Cache-Control: no-store` for book
   details. These measurements therefore reach MySQL.
2. A page request reads `size + 1`, which is 21 rows for a 20-item page. It does not execute a total
   count and does not load all five million books into the application.
3. MySQL can seek through `idx_book_status_id(status, book_id)` using
   `status = 'PUBLISHED' AND book_id > afterBookId`. The stock join then performs primary-key lookups
   for only the selected rows.
4. The measured JSON responses were about 8.5-8.7 KiB. The load generator, application container,
   and MySQL container all ran on the same physical machine, so this baseline contains Docker bridge
   and loopback latency rather than Internet or cross-zone latency.
5. Each scenario used twenty warm-up requests. The table is a warm-state comparison baseline.

The values are not all "ten milliseconds": p50 is 5.73-10.09 ms, while p95 is 75.05-97.69 ms and
p99 is 77.88-103.23 ms under the fixed 2-CPU limits. The cgroups recorded CPU throttling, which is
consistent with the higher tail under concurrency 20. These results establish a same-machine
before/after cache comparison; they are not a production-capacity claim.

## OFFSET comparison

The application does not expose OFFSET pagination. The following results are single MySQL
`EXPLAIN ANALYZE` executions against the same published-book ordering; they demonstrate the cost of
an OFFSET implementation and are not percentile measurements.

| Position | OFFSET | Rows read | Actual time |
| --- | ---: | ---: | ---: |
| 0% | 0 | 21 | 0.274 ms |
| 25% | 1,125,000 | about 1.13 million | 281 ms |
| 50% | 2,250,000 | about 2.25 million | 554 ms |
| 75% | 3,375,000 | about 3.38 million | 852 ms |
| 90% | 4,050,000 | about 4.05 million | 1,004 ms |
| 99% | 4,455,000 | about 4.46 million | 1,094 ms |
| 99.9% | 4,495,500 | about 4.50 million | 1,100 ms |

At 99.9%, an equivalent keyset query read 21 rows and completed its database range scan in about
0.035 ms in the sampled execution. OFFSET read about 4.50 million index entries and took about
1.10 seconds.

## Title-prefix query baseline

The keyset table above does not include `q`. The endpoint implements a title-prefix predicate,
`b.title LIKE CONCAT(#{query}, '%')`; it does not implement an arbitrary substring predicate such as
`LIKE '%query%'`. A separate host-side `curl.exe --parallel` run measured the actual HTTP endpoint:

- Completion: 2026-11-21 00:53:37.207 +08:00
- 100 measured requests per scenario, 300 total
- Concurrency: 20
- Page size: 20, with no `afterBookId` supplied
- MyBatis statement timeout: 3 seconds
- One timeout-reproduction probe preceded each measured scenario

The generated titles selected below occur near the end of the primary-key order. This is a verified
late-match/deep-scan case, not a claim that every title prefix has the same cost.

| Scenario | Matching published rows | RPS | p50 | p95 | p99 | Max | Business success |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `q=Load Test Book 499` | 9,000 | 4.90 | 3,282.26 ms | 7,371.96 ms | 7,373.03 ms | 7,378.21 ms | 0/100 |
| `q=Load Test Book 49` | 90,000 | 5.15 | 3,282.70 ms | 6,287.54 ms | 6,289.32 ms | 6,289.73 ms | 0/100 |
| 9K prefix plus category/language/format | 2,333 | 5.22 | 3,196.39 ms | 6,231.61 ms | 6,236.13 ms | 6,239.66 ms | 0/100 |

All 300 measured requests returned HTTP 200, but every response contained
`code: INTERNAL_ERROR`; none contained `code: 0000`. The percentiles in this table are therefore
**failure-response latency**, not successful search latency. Application logs increased by 303
`Unhandled request error` entries across the three probes and 300 measured requests. The corresponding
root cause was `MySQLTimeoutException: Statement cancelled due to timeout or client request`.
After the measured run, the application health endpoint remained `UP`; both constrained containers
were still running and reported no OOM kill.

The complete API-equivalent SQL selects all book and stock fields. Direct `EXPLAIN ANALYZE` runs,
which were not subject to the application's three-second timeout, produced these plans:

| Scenario | Book rows scanned | Time to 21 joined rows |
| --- | ---: | ---: |
| 9K prefix | about 4.99 million | 5.978 s |
| 90K prefix | about 4.90 million | 5.821 s |
| 9K prefix plus filters | about 4.99 million | 5.701 s |

All three plans used a primary-key range scan to satisfy `ORDER BY b.book_id LIMIT 21`, followed by
primary-key stock lookups. The existing `(title, status, book_id)` index did not provide the chosen
plan for this complete query shape. A prior simplified `SELECT book_id` plan used a covering title
index and was not representative of the HTTP endpoint; it has been removed from this report.

For a conventional cache-aside implementation, these keys cannot populate the cache from a cold
miss because the database load ends in a timeout. Query-plan/index optimization or a separately
precomputed/prewarmed search result is required before these searches can produce cacheable results.
Post-cache measurements must report cache-hit and cache-miss behavior separately.

## Pagination resource and correctness checks

- Application log error lines during the 8,000-request pagination run: 0
- MySQL log error lines: 0
- Application health after the run: `UP`
- Application OOM and OOM-kill events: 0
- MySQL OOM and OOM-kill events: 0
- MySQL recorded 80 `memory.max` pressure events during data generation without an OOM

## Artifacts and rerun

- Five-million-row generator: `load-tests/seed-catalog-5m.sql`
- Deep-pagination driver: `load-tests/deep-pagination-benchmark.ps1`
- Title-prefix driver: `load-tests/prefix-query-benchmark.ps1`
- Pagination raw result: `.tmp/pre-cache-container-5m-deep-pagination.json`
- Title-prefix raw result: `.tmp/pre-cache-container-5m-prefix-failure.json`

Run the same workload after adding caching:

```powershell
.\load-tests\deep-pagination-benchmark.ps1 `
  -BaseUrl http://127.0.0.1:18080 `
  -Requests 1000 `
  -Concurrency 20 `
  -OutputPath .tmp/post-cache-container-5m-deep-pagination.json
```

Run the same title-prefix workload after adding caching with:

```powershell
.\load-tests\prefix-query-benchmark.ps1 `
  -BaseUrl http://127.0.0.1:18080 `
  -Requests 100 `
  -Concurrency 20 `
  -OutputPath .tmp/post-cache-container-5m-prefix.json
```

