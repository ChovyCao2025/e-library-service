-- Expands the deterministic benchmark catalog to 5,000,000 generated books.
-- Run this only against an isolated test schema. Batches are idempotent.
SET SESSION cte_max_recursion_depth = 100001;

DROP PROCEDURE IF EXISTS seed_benchmark_catalog_5m;
DELIMITER //
CREATE PROCEDURE seed_benchmark_catalog_5m()
BEGIN
  DECLARE batch_start INT DEFAULT 100000;
  DECLARE batch_end INT;

  WHILE batch_start < 5000000 DO
    SET batch_end = batch_start + 99999;

    INSERT IGNORE INTO book(
      book_id, isbn, title, subtitle, authors, publisher, category_id, language,
      description, cover_url, published_date, format, status, version, created_at, updated_at
    )
    WITH RECURSIVE seq(n) AS (
      SELECT 0
      UNION ALL
      SELECT n + 1 FROM seq WHERE n < 99999
    )
    SELECT 3000000 + batch_start + n,
           CONCAT('979', LPAD(batch_start + n, 10, '0')),
           CONCAT('Load Test Book ', LPAD(batch_start + n, 7, '0')),
           IF((batch_start + n) % 10 = 0,
              CONCAT('Volume ', (batch_start + n) % 100), NULL),
           JSON_ARRAY(CONCAT('Load Author ', (batch_start + n) % 1000)),
           CONCAT('Load Publisher ', (batch_start + n) % 100),
           ((batch_start + n) % 3) + 1,
           IF((batch_start + n) % 4 = 0, 'zh-CN', 'en'),
           CONCAT('Generated five-million-row benchmark catalog record ', batch_start + n, '.'),
           NULL,
           DATE_ADD('2010-01-01', INTERVAL ((batch_start + n) % 6000) DAY),
           IF((batch_start + n) % 8 = 0, 'JOURNAL', 'EBOOK'),
           CASE
             WHEN (batch_start + n) % 20 = 0 THEN 'OFFLINE'
             WHEN (batch_start + n) % 20 = 1 THEN 'DRAFT'
             ELSE 'PUBLISHED'
           END,
           1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
    FROM seq;

    INSERT IGNORE INTO lending_stock(
      book_id, total_copies, available_copies, version, updated_at
    )
    SELECT book_id, 5 + (book_id % 16), 5 + (book_id % 16), 0, UTC_TIMESTAMP(3)
    FROM book
    WHERE book_id BETWEEN 3000000 + batch_start AND 3000000 + batch_end;

    SELECT batch_end + 1 AS generated_rows;
    SET batch_start = batch_start + 100000;
  END WHILE;
END//
DELIMITER ;

CALL seed_benchmark_catalog_5m();
DROP PROCEDURE seed_benchmark_catalog_5m;

ANALYZE TABLE book, lending_stock;

SELECT COUNT(*) AS books,
       SUM(status = 'PUBLISHED') AS published,
       SUM(status = 'DRAFT') AS drafts,
       SUM(status = 'OFFLINE') AS offline
FROM book;

