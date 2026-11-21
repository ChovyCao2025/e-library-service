-- Adds 100,000 deterministic catalog rows for cache and query benchmarks.
-- Run this only against an isolated test schema. Re-running it is idempotent.
SET SESSION cte_max_recursion_depth = 100001;

INSERT IGNORE INTO book(
  book_id, isbn, title, subtitle, authors, publisher, category_id, language,
  description, cover_url, published_date, format, status, version, created_at, updated_at
)
WITH RECURSIVE seq(n) AS (
  SELECT 0
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 99999
)
SELECT 3000000 + n,
       CONCAT('979', LPAD(n, 10, '0')),
       CONCAT('Load Test Book ', LPAD(n, 6, '0')),
       IF(n % 10 = 0, CONCAT('Volume ', n % 100), NULL),
       JSON_ARRAY(CONCAT('Load Author ', n % 1000)),
       CONCAT('Load Publisher ', n % 100),
       (n % 3) + 1,
       IF(n % 4 = 0, 'zh-CN', 'en'),
       CONCAT('Generated pre-cache benchmark catalog record ', n,
              '. This row is isolated in the API test schema.'),
       NULL,
       DATE_ADD('2010-01-01', INTERVAL (n % 6000) DAY),
       IF(n % 8 = 0, 'JOURNAL', 'EBOOK'),
       CASE
         WHEN n % 20 = 0 THEN 'OFFLINE'
         WHEN n % 20 = 1 THEN 'DRAFT'
         ELSE 'PUBLISHED'
       END,
       1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM seq;

INSERT IGNORE INTO lending_stock(
  book_id, total_copies, available_copies, version, updated_at
)
SELECT book_id, 5 + (book_id % 16), 5 + (book_id % 16), 0, UTC_TIMESTAMP(3)
FROM book
WHERE book_id BETWEEN 3000000 AND 3099999;

ANALYZE TABLE book, lending_stock;

SELECT COUNT(*) AS books,
       SUM(status = 'PUBLISHED') AS published,
       SUM(status = 'DRAFT') AS drafts,
       SUM(status = 'OFFLINE') AS offline
FROM book;

