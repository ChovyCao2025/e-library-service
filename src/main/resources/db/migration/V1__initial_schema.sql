CREATE TABLE category (
  category_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book (
  book_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  isbn VARCHAR(20) NULL,
  title VARCHAR(200) NOT NULL,
  subtitle VARCHAR(200) NULL,
  authors JSON NOT NULL,
  publisher VARCHAR(100) NULL,
  category_id BIGINT UNSIGNED NOT NULL,
  language VARCHAR(10) NOT NULL,
  description TEXT NULL,
  cover_url VARCHAR(500) NULL,
  published_date DATE NULL,
  format VARCHAR(10) NOT NULL,
  status VARCHAR(10) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_book_isbn (isbn),
  KEY idx_book_category_status (category_id, status, book_id),
  KEY idx_book_status_id (status, book_id),
  KEY idx_book_title_status_id (title, status, book_id),
  CONSTRAINT ck_book_status CHECK (status IN ('DRAFT','PUBLISHED','OFFLINE')),
  CONSTRAINT ck_book_format CHECK (format IN ('EBOOK','JOURNAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE lending_stock (
  book_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  total_copies INT NOT NULL DEFAULT 0,
  available_copies INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT ck_stock CHECK (total_copies BETWEEN 0 AND 100000 AND available_copies BETWEEN 0 AND total_copies)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_lending_account (
  user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  active_count INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT ck_account_count CHECK (active_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE projection_control (
  resource_type VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resource_id BIGINT UNSIGNED NOT NULL,
  generation BIGINT NOT NULL DEFAULT 1,
  state VARCHAR(12) NOT NULL DEFAULT 'REBUILDING',
  owner_token VARCHAR(80) NULL,
  lease_until DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (resource_type, resource_id),
  KEY idx_projection_rebuild (state, lease_until),
  CONSTRAINT ck_projection_type CHECK (resource_type IN ('BOOK','USER')),
  CONSTRAINT ck_projection_state CHECK (state IN ('READY','DIRTY','REBUILDING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE borrow_request (
  request_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  idem_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  book_id BIGINT UNSIGNED NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  loan_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(12) NOT NULL,
  attempt INT NOT NULL DEFAULT 1,
  attempt_token VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  admission_mode VARCHAR(8) NOT NULL DEFAULT 'REDIS',
  quota_generation BIGINT NULL,
  stock_generation BIGINT NULL,
  lease_until DATETIME(3) NOT NULL,
  quota_settled TINYINT NOT NULL DEFAULT 0,
  stock_settled TINYINT NOT NULL DEFAULT 0,
  result_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_request_user_idem (user_id, idem_key),
  UNIQUE KEY uk_request_loan (loan_id),
  KEY idx_request_recovery (status, lease_until, request_id),
  CONSTRAINT ck_admission_mode CHECK (admission_mode IN ('REDIS','DB_ONLY')),
  CONSTRAINT ck_request_status CHECK (status IN ('PROCESSING','SUCCEEDED','RELEASING','RETRYABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE loan (
  loan_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  request_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  book_id BIGINT UNSIGNED NOT NULL,
  idem_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(10) NOT NULL,
  active_key TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'BORROWED' THEN 1 ELSE NULL END) STORED,
  borrowed_at DATETIME(3) NOT NULL,
  due_at DATETIME(3) NOT NULL,
  returned_at DATETIME(3) NULL,
  return_reason VARCHAR(10) NULL,
  policy_version VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_loan_request (request_id),
  UNIQUE KEY uk_user_idem (user_id, idem_key),
  UNIQUE KEY uk_user_book_active (user_id, book_id, active_key),
  KEY idx_user_status_time (user_id, status, borrowed_at, loan_id),
  KEY idx_book_active (book_id, active_key, loan_id),
  KEY idx_status_due (status, due_at, loan_id),
  CONSTRAINT ck_loan_status CHECK (status IN ('BORROWED','RETURNED')),
  CONSTRAINT ck_loan_dates CHECK (due_at > borrowed_at),
  CONSTRAINT ck_loan_return CHECK ((status = 'BORROWED' AND returned_at IS NULL AND return_reason IS NULL) OR (status = 'RETURNED' AND returned_at IS NOT NULL AND return_reason IS NOT NULL AND return_reason IN ('MANUAL','EXPIRED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  aggregate_type VARCHAR(30) NOT NULL,
  aggregate_id BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  aggregate_version BIGINT NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'NEW',
  owner_token VARCHAR(80) NULL,
  lease_until DATETIME(3) NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  sent_at DATETIME(3) NULL,
  last_error VARCHAR(1000) NULL,
  KEY idx_outbox_ready (status, next_attempt_at, id),
  KEY idx_outbox_lease (status, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consumer_receipt (
  consumer_group VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  applied_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (consumer_group, target_key, event_id),
  KEY idx_receipt_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_write_gate (
  id TINYINT NOT NULL PRIMARY KEY,
  state VARCHAR(8) NOT NULL,
  owner_token VARCHAR(80) NULL,
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE search_generation (
  index_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  state VARCHAR(12) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE search_delivery (
  event_id BIGINT UNSIGNED NOT NULL,
  index_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (event_id, index_name),
  KEY idx_search_pending (index_name, status, event_id)
) ENGINE=InnoDB;

CREATE TABLE search_reindex_job (
  job_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  target_index VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) NOT NULL,
  owner_token VARCHAR(80) NULL,
  lease_until DATETIME(3) NULL,
  scan_after BIGINT UNSIGNED NULL,
  last_error VARCHAR(1000) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_reindex_state (state, lease_until)
) ENGINE=InnoDB;
