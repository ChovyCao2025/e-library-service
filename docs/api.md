# HTTP API

預設 base URL：`http://localhost:8080/api/v1`

## 共通約定

- Request/response 使用 `application/json`。
- 路徑與查詢 ID 由 Spring 直接轉換為正整數；JSON 請求接受整數或十進位字串，回應仍以十進位字串輸出以避免 JavaScript 精度遺失。
- 所有時間使用 UTC ISO-8601。
- 開發模式預設使用者為 `10001 / USER`。
- 可用 `X-User-Id` 與 `X-Role` 模擬受信任閘道身分。

### 回應模型

所有業務 API 統一回傳 `Resp<T>`：

```json
{
  "code": "0000",
  "msg": "success",
  "data": {}
}
```

- `code`：穩定業務碼；成功固定為 `0000`，失敗使用錯誤碼。
- `msg`：可讀結果說明，不作為程式判斷依據。
- `data`：端點回應資料；沒有資料時固定為 `null`。

所有業務碼與預設訊息集中定義於 `ErrorCode`，呼叫端只依 `code` 分支處理。

應用程式已處理的業務請求統一回傳 `200 OK`，業務結果由 `code` 表達：

- 建立及查詢成功回傳 `code: 0000`。
- 冪等重放同樣回傳 `code: 0000`，並加入 `Idempotent-Replay: true`。
- 分頁資料位於 `data`。書目列表回傳 `nextBookId`，借閱列表回傳 `nextLoanId`；最後一頁皆為 `null`。

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
| `afterBookId` | 否 | 前一頁回傳的 `nextBookId` |
| `size` | 否 | `1–50`，預設 `20` |

只回傳 `PUBLISHED` 書籍。

`SEARCH_ENABLED=false` 時所有條件由 MySQL 查詢。`SEARCH_ENABLED=true` 且 `q` 有值時，
Elasticsearch 依標準化書名前綴、可選篩選條件及 `bookId` 排序查詢；Lending MySQL 再以本頁
書籍 ID 批量補上即時庫存。Elasticsearch 暫時無法服務時回傳可重試的
`SEARCH_UNAVAILABLE`，避免大量搜尋直接壓回 MySQL。

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

- 首次成功：`code: 0000`
- 相同鍵與相同 `bookId` 重放：`code: 0000`，並回傳 `Idempotent-Replay: true`
- 相同鍵改用其他 `bookId`：`code: IDEMPOTENCY_KEY_REUSED`

成功回應：

```json
{
  "code": "0000",
  "msg": "success",
  "data": {
    "requestId": "249187489812578305",
    "loanId": "249187489820966913",
    "bookId": "2000001",
    "status": "BORROWED",
    "borrowedAt": "2026-11-19T15:02:00Z",
    "dueAt": "2026-12-03T15:02:00Z"
  }
}
```

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

`status` 支援 `BORROWED` 與 `RETURNED`。下一頁將前一頁的 `nextLoanId` 作為 `afterLoanId` 傳入。

## 管理 API

以下 API 需要 `ADMIN` 角色：

| 方法 | 路徑 | 說明 |
| --- | --- | --- |
| `POST` | `/admin/books` | 建立 DRAFT 書籍 |
| `PUT` | `/admin/books/{bookId}` | 以 version 樂觀鎖更新書目 |
| `POST` | `/admin/books/{bookId}/publish` | 上架書籍 |
| `POST` | `/admin/books/{bookId}/offline` | 下架書籍 |
| `PUT` | `/admin/books/{bookId}/stock` | 調整總借閱席位 |

建立書目時由後端初始化版本。更新書目時，請求中的 `version` 應原樣帶入頁面先前查詢到的書目版本；版本不一致時回傳 `VERSION_CONFLICT`。上架與下架以目前 `status` 作為狀態遷移條件，不要求版本參數。

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

錯誤回應沿用相同的 `code`、`msg`、`data` 外層；HTTP 傳輸狀態為 `200 OK`：

```json
{
  "code": "BOOK_SOLD_OUT",
  "msg": "No lending seat is available",
  "data": {
    "traceId": "c469ae55-7d36-4a33-a610-bdd29ba319f9",
    "retryable": false,
    "requestId": "249187489812578305"
  }
}
```

常見錯誤碼：

| 錯誤碼 | 說明 |
| --- | --- |
| `VALIDATION_FAILED` | 請求格式或參數不合法 |
| `MISSING_IDEMPOTENCY_KEY` | 借閱缺少冪等鍵 |
| `UNAUTHENTICATED` | 缺少可信身分 |
| `FORBIDDEN` | 權限不足 |
| `BOOK_NOT_FOUND` | 書籍不存在或不可見 |
| `BOOK_SOLD_OUT` | 無可借席位 |
| `ALREADY_BORROWED` | 使用者已借閱同一本書 |
| `LOAN_LIMIT_EXCEEDED` | 已達使用者借閱上限 |
| `VERSION_CONFLICT` | 書目版本或狀態遷移條件已改變 |
| `IDEMPOTENCY_KEY_REUSED` | 冪等鍵被用於其他書籍 |
| `SEARCH_UNAVAILABLE` | Elasticsearch 搜尋投影暫時無法查詢，可重試 |
