INSERT INTO category(category_id, name, parent_id) VALUES
  (1, 'Computer Science', 0),
  (2, 'Literature', 0),
  (3, 'Business', 0);

INSERT INTO catalog_write_gate(id, state, owner_token, updated_at) VALUES (1, 'OPEN', NULL, UTC_TIMESTAMP(3));
INSERT INTO search_generation(index_name, state, created_at, updated_at) VALUES ('books_v1', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

INSERT INTO book(book_id, isbn, title, subtitle, authors, publisher, category_id, language, description, cover_url, published_date, format, status, version, created_at, updated_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100
)
SELECT 1000000 + n,
       CONCAT('9780000', LPAD(n, 6, '0')),
       CONCAT('E-Library Demo Book ', n),
       NULL,
       JSON_ARRAY(CONCAT('Author ', ((n - 1) MOD 10) + 1)),
       'E-Library Press',
       ((n - 1) MOD 3) + 1,
       IF(n MOD 4 = 0, 'zh-CN', 'en'),
       CONCAT('Seeded catalog item ', n, ' for local development and query tests.'),
       NULL,
       DATE_ADD('2020-01-01', INTERVAL n DAY),
       IF(n MOD 8 = 0, 'JOURNAL', 'EBOOK'),
       'PUBLISHED', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM seq;

INSERT INTO lending_stock(book_id, total_copies, available_copies, version, updated_at)
SELECT book_id, 5, 5, 0, UTC_TIMESTAMP(3) FROM book;

INSERT INTO projection_control(resource_type, resource_id, generation, state, updated_at)
SELECT 'BOOK', book_id, 1, 'DIRTY', UTC_TIMESTAMP(3) FROM book;

INSERT INTO book(book_id, isbn, title, subtitle, authors, publisher, category_id, language, description, cover_url, published_date, format, status, version, created_at, updated_at)
VALUES (2000001, '9780000999999', 'Hotspot Lending Demo', 'Concurrency test title', JSON_ARRAY('E-Library Team'), 'E-Library Press', 1, 'en', 'A single-seat title for contention and idempotency tests.', NULL, '2026-01-01', 'EBOOK', 'PUBLISHED', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
INSERT INTO lending_stock(book_id, total_copies, available_copies, version, updated_at) VALUES (2000001, 1, 1, 0, UTC_TIMESTAMP(3));
INSERT INTO projection_control(resource_type, resource_id, generation, state, updated_at) VALUES ('BOOK', 2000001, 1, 'DIRTY', UTC_TIMESTAMP(3));
