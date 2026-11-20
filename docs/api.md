# HTTP API

預設 base URL：`http://localhost:8080/api/v1`

## 共通約定

- Request/response 使用 `application/json`。
- 所有 ID 使用十進位字串。
- 所有時間使用 UTC ISO-8601。
- 開發模式預設使用者為 `10001 / USER`。
- 可用 `X-User-Id` 與 `X-Role` 模擬受信任閘道身分。

### 回應模型

成功回應直接回傳具型別的資源 DTO，並使用 HTTP 狀態碼與標頭表達結果，例如：

- 建立借閱使用 `201 Created` 與 `Location`。
- 冪等重放使用 `200 OK` 與 `Idempotent-Replay: true`。
- 游標分頁 DTO 統一包含 `items`、`nextCursor` 與 `hasMore`。

錯誤回應統一使用 `ProblemResponse` 與 `application/problem+json`。本專案刻意不增加
`code/message/data` 成功包裝層，避免重複 HTTP 狀態語義，並保留快取、Location 與不同資源模型的表達能力。

## 書目 API

### 瀏覽書籍

```http
GET /books?q=Demo&categoryId=1&language=en&format=EBOOK&size=20
```

| 參數 | 必填 | 說明 |
| --- | --- | --- |
| `q` | 否 | 書名開頭比對，最多 100 字元 |
| `categoryId` | 否 | 分類 ID |
| `language` | 否 | 語言代碼 |
| `format` | 否 | `EBOOK` 或 `JOURNAL` |
| `cursor` | 否 | 前一頁回傳的簽章游標 |
| `size` | 否 | `1–50`，預設 `20` |

只回傳 `PUBLISHED` 書籍。

### 查詢書籍詳細資料

```http
GET /books/{bookId}
```

找不到或尚未上架的書籍回傳 `BOOK_NOT_FOUND`。

## 借閱 API

### 借閱書籍

```http
POST /loans
Idempotency-Key: demo-borrow-001
Content-Type: application/json

{
  "bookId": "2000001"
}
```

- 首次成功：`201 Created`
- 相同鍵與相同參數重放：`200 OK`，並回傳 `Idempotent-Replay: true`
- 相同鍵與不同參數：`422 Unprocessable Entity`

成功回應：

```json
{
  "requestId": "249187489812578305",
  "loanId": "249187489820966913",
  "bookId": "2000001",
  "status": "BORROWED",
  "borrowedAt": "2026-11-19T15:02:00Z",
  "dueAt": "2026-12-03T15:02:00Z"
}
```

### 查詢借閱請求

```http
GET /borrow-requests/{requestId}
```

用於逾時後查詢持久化請求狀態。

### 查詢單筆借閱

```http
GET /loans/{loanId}
```

只能讀取目前使用者所屬的 Loan。

### 歸還書籍

```http
POST /loans/{loanId}/return
```

重複歸還會回傳第一次歸還後的穩定結果，不會重複釋放庫存或配額。

### 查看目前借閱

```http
GET /users/me/loans?status=BORROWED&size=20
```

`status` 支援 `BORROWED` 與 `RETURNED`，並可使用 `cursor` 分頁。

## 管理 API

以下 API 需要 `ADMIN` 角色：

| 方法 | 路徑 | 說明 |
| --- | --- | --- |
| `POST` | `/admin/books` | 建立 DRAFT 書籍 |
| `PUT` | `/admin/books/{bookId}` | 以 version 樂觀鎖更新書目 |
| `POST` | `/admin/books/{bookId}/publish` | 上架書籍 |
| `POST` | `/admin/books/{bookId}/offline` | 下架書籍 |
| `PUT` | `/admin/books/{bookId}/stock` | 調整總借閱席位 |

調整庫存示例：

```http
PUT /admin/books/2000001/stock
X-User-Id: 10002
X-Role: ADMIN
Content-Type: application/json

{
  "totalCopies": 5
}
```

## 錯誤格式

錯誤回應使用 `application/problem+json`：

```json
{
  "type": "urn:elibrary:error:book_sold_out",
  "title": "BOOK_SOLD_OUT",
  "status": 409,
  "code": "BOOK_SOLD_OUT",
  "detail": "No lending seat is available",
  "traceId": "c469ae55-7d36-4a33-a610-bdd29ba319f9",
  "retryable": false,
  "requestId": "249187489812578305"
}
```

常見錯誤碼：

| 錯誤碼 | HTTP | 說明 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 請求格式或參數不合法 |
| `MISSING_IDEMPOTENCY_KEY` | 400 | 借閱缺少冪等鍵 |
| `UNAUTHENTICATED` | 401 | 缺少可信身分 |
| `FORBIDDEN` | 403 | 權限不足 |
| `BOOK_NOT_FOUND` | 404 | 書籍不存在或不可見 |
| `BOOK_SOLD_OUT` | 409 | 無可借席位 |
| `ALREADY_BORROWED` | 409 | 使用者已借閱同一本書 |
| `LOAN_LIMIT_EXCEEDED` | 409 | 已達使用者借閱上限 |
| `REQUEST_IN_PROGRESS` | 409 | 相同請求仍在處理，可稍後重試 |
| `IDEMPOTENCY_KEY_REUSED` | 422 | 冪等鍵被用於不同參數 |
| `SERVICE_UNAVAILABLE` | 503 | 依賴或投影正在恢復 |
