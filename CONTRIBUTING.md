# 貢獻指南

感謝參與 E-Library。此專案以清楚、可驗證的業務規則為優先，提交內容應保持範圍集中。

## 開發環境

1. 安裝 JDK 21 與 Docker。
2. 執行 `docker compose up -d mysql redis`。
3. 執行 `./mvnw spring-boot:run`，Windows 使用 `.\mvnw.cmd spring-boot:run`。
4. 提交前執行 `./mvnw clean verify`。

## 程式碼規範

- 使用既有的 `catalog`、`lending`、`shared` 模組邊界。
- Application 層負責用例與交易；Controller 只處理 HTTP 轉換與身分取得。
- MyBatis Mapper 只保留方法契約，SQL 統一放在 `src/main/resources/mybatis`。
- SQL 使用顯式欄位清單，避免星號查詢。
- 已發布的 Flyway migration 不得修改；資料庫變更必須新增下一個版本。
- 複雜註解使用繁體中文，說明原因、不變量與失敗語義，避免逐行重述程式碼。
- 對外 ID 使用字串，時間使用 UTC。
- 新增外部副作用時，確認它位於資料庫交易之外或具備明確的一致性機制。

## 測試要求

- 修改純函式或驗證規則時加入單元測試。
- 修改 SQL、交易或資料庫約束時加入 MySQL 整合測試。
- 修改模組依賴或持久層規範時更新 ArchUnit 測試。
- 修正缺陷時先建立可重現案例，再驗證不變量。

## Pull Request

PR 描述應包含：

- 問題與範圍。
- 採用方案及主要取捨。
- 測試方式與結果。
- 資料庫、API 或設定相容性影響。
- 未完成事項或已知風險。
