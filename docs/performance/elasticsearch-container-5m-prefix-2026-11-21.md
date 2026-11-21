# Elasticsearch 5M title-prefix benchmark

## Scope

This report compares the Elasticsearch catalog projection with the existing MySQL title-prefix
baseline using the same 5,000,101-book database, HTTP endpoint, query scenarios, page size, request
count, and concurrency. The measured application used commit `e74f686` plus the uncommitted search
class refactor and the query/index optimizations recorded below.

The request path is:

1. Elasticsearch prefix and term-filter query, sorted by `bookIdSort`.
2. One MySQL `lending_stock WHERE book_id IN (...)` query for the returned page.
3. HTTP serialization of the combined catalog and authoritative stock view.

## Environment

- Completion date: 2026-11-21, Asia/Shanghai.
- Application: Java 21.0.13, Spring Boot 4.0.9, 2 CPU, 1 GiB memory, no additional swap.
- MySQL: 8.4.7, 2 CPU, 2 GiB memory, existing five-million-row benchmark database.
- Elasticsearch: 9.2.0, 2 CPU, 1 GiB memory, 512 MiB heap.
- Index: one primary shard, zero replicas, `catalog-book-v1-000001`.
- Application DB pool: 20.
- Load: 100 measured requests per scenario, concurrency 20, page size 20.
- Driver: `load-tests/prefix-query-benchmark.ps1` using `curl.exe --parallel`.

## Initial backfill

The application ran with `SEARCH_REBUILD_ON_STARTUP=true` and `SEARCH_BATCH_SIZE=5000`. It scanned
MySQL in ascending `book_id` batches and used Elasticsearch bulk writes with external versions.

| Metric | Result |
| --- | ---: |
| Documents indexed | 5,000,101 |
| Spring `Started` to backfill completion | 702.47 s (11m 42.47s) |
| Average indexing rate | 7,117.89 documents/s |
| Primary index store | 628 MiB |
| Active merges before load | 0 |

The application and Elasticsearch containers remained running with zero restarts and zero OOM kills.
The 1 GiB Elasticsearch cgroup recorded memory-pressure events and CPU throttling during indexing,
so the backfill duration is specific to this constrained setup.

## MySQL baseline versus first ES run

The MySQL p95 values below are failure-response latency: all requests reached HTTP 200 with
`code=INTERNAL_ERROR` after the statement timeout. The ES p95 values are successful response latency,
so they must not be interpreted as a direct latency speedup ratio.

| Scenario | MySQL success | MySQL RPS | MySQL p95 | ES success | ES RPS | ES p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 9K prefix | 0/100 | 4.90 | 7,371.96 ms | 100/100 | 62.09 | 390.73 ms |
| 90K prefix | 0/100 | 5.15 | 6,287.54 ms | 100/100 | 60.69 | 438.27 ms |
| 9K prefix plus filters | 0/100 | 5.22 | 6,231.61 ms | 100/100 | 116.86 | 195.69 ms |

This first ES run began after backfill completion, index refresh, and verification that no merge was
active. It was retained separately because the next two runs showed materially higher warm-state
throughput.

## Repeated warm-state ES runs

| Scenario | RPS range | p50 range | p95 range | p99 range | Business success |
| --- | ---: | ---: | ---: | ---: | ---: |
| 9K prefix | 135.91–154.38 | 100.88–104.54 ms | 174.55–180.78 ms | 183.31–185.65 ms | 200/200 |
| 90K prefix | 79.34–79.52 | 202.47–210.02 ms | 296.20–368.83 ms | 318.89–386.84 ms | 200/200 |
| 9K prefix plus filters | 148.44–166.00 | 87.64–102.89 ms | 131.16–191.44 ms | 156.20–194.15 ms | 200/200 |

Across all three ES runs, all 900 measured requests returned HTTP 200 and `code=0000`. Elasticsearch
recorded 910 total queries, including probes, with zero query and fetch failures. A manual response
check confirmed that stock was still assembled from `availability.source=DATABASE`.

## Direct greenfield tuning

The project has no deployed index compatibility requirement, so the current physical index definition
was changed directly. No migration, dual-write, or version-switch path was added. The tuned setup uses:

- `track_total_hits=false` because the API only needs one extra result for `hasMore`.
- Prefix and exact-match conditions in filter context, with no relevance scoring.
- `bookIdSort ASC` index sorting matching the request sort.
- A `keyword`-analyzed text field with `index_prefixes` from 1 through 19 characters.
- A short MySQL transaction only for the authoritative stock lookup.
- A 5,000-document backfill batch and refresh disabled until the final refresh.

### Tuned backfill cost

| Metric | Before | Tuned | Change |
| --- | ---: | ---: | ---: |
| Spring `Started` to completion | 702.47 s | 766.67 s | +9.14% |
| Average indexing rate | 7,117.89 docs/s | 6,521.89 docs/s | -8.37% |
| Primary index store | 628 MiB | 694 MiB | +10.51% |

Both backfills completed with 5,000,101 documents, zero restarts, and zero OOM kills. The extra cost
comes with the prefix terms and segment sorting required by the read optimization.

### Tuned first run

| Scenario | Before RPS | Tuned RPS | Before p95 | Tuned p95 | Success |
| --- | ---: | ---: | ---: | ---: | ---: |
| 9K prefix | 62.09 | 85.87 | 390.73 ms | 283.63 ms | 100/100 |
| 90K prefix | 60.69 | 111.04 | 438.27 ms | 193.22 ms | 100/100 |
| 9K prefix plus filters | 116.86 | 115.40 | 195.69 ms | 192.50 ms | 100/100 |

### Tuned repeated warm-state runs

| Scenario | Before RPS range | Tuned RPS range | Before p95 range | Tuned p95 range | Success |
| --- | ---: | ---: | ---: | ---: | ---: |
| 9K prefix | 135.91–154.38 | 130.33–153.97 | 174.55–180.78 ms | 170.44–181.59 ms | 200/200 |
| 90K prefix | 79.34–79.52 | 135.60–158.96 | 296.20–368.83 ms | 174.44–191.63 ms | 200/200 |
| 9K prefix plus filters | 148.44–166.00 | 138.32–160.23 | 131.16–191.44 ms | 157.67–181.64 ms | 200/200 |

The broad 90K prefix is the material improvement: warm throughput increased by 70.52–100.35%, while
p95 fell by 35.30–52.70%. The narrower scenarios stayed in the same general range, with normal
run-to-run variation at only 100 requests per scenario.

Elasticsearch recorded 910 queries in each three-run sequence. Aggregate ES query time fell from
21,483 ms before tuning to 1,604 ms after tuning, with zero query and fetch failures in both runs.
End-to-end HTTP latency remains higher because it also includes request queuing, MySQL stock lookup,
view assembly, and JSON serialization.

## Conclusion

The Elasticsearch projection turns the verified late-match prefix workload from 0/300 successful
MySQL responses into 900/900 successful ES-backed responses under the same application and MySQL
limits. After direct greenfield tuning, the two repeated runs sustained 130.33–160.23 RPS with p95
between 157.67 and 191.63 ms. The main gain is predictable latency for the broad 90K prefix.

The constrained first backfill completed successfully in 11m 42.47s. Deployment planning should
treat that cost separately from steady-state event indexing and query latency.

## Artifacts and rerun

- MySQL baseline: `.tmp/pre-cache-container-5m-prefix-failure.json`
- First ES run: `.tmp/post-es-container-5m-prefix.json`
- Warm ES run 1: `.tmp/post-es-container-5m-prefix-repeat.json`
- Warm ES run 2: `.tmp/post-es-container-5m-prefix-repeat-2.json`
- Tuned first ES run: `.tmp/post-es-tuned-container-5m-prefix.json`
- Tuned warm ES run 1: `.tmp/post-es-tuned-container-5m-prefix-repeat.json`
- Tuned warm ES run 2: `.tmp/post-es-tuned-container-5m-prefix-repeat-2.json`

```powershell
.\load-tests\prefix-query-benchmark.ps1 `
  -BaseUrl http://127.0.0.1:18081 `
  -Requests 100 `
  -Concurrency 20 `
  -OutputPath .tmp/post-es-container-5m-prefix.json
```
