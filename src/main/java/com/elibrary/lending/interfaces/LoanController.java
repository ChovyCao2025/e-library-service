package com.elibrary.lending.interfaces;

import com.elibrary.lending.application.LendingService;
import com.elibrary.shared.web.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 提供目前使用者的借閱生命週期 API。所有資源查詢都帶入可信 userId，
 * 避免使用者透過可猜測的 loanId 或 requestId 存取他人資料。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class LoanController {
    private final LendingService service;

    public LoanController(LendingService service) {
        this.service = service;
    }

    /**
     * 建立借閱。Idempotency-Key 與請求指紋會持久化；相同請求重送時回傳原 Loan。
     */
    @PostMapping("/loans")
    public ResponseEntity<LendingDtos.LoanResponse> borrow(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LendingDtos.BorrowRequest request) {
        long userId = UserContext.require().userId();
        long bookId = parseId(request.bookId());
        var result = service.borrow(userId, bookId, idempotencyKey);
        var builder = ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/loans/" + result.loan().loanId()));
        if (result.replay()) builder.header("Idempotent-Replay", "true");
        return builder.body(result.loan());
    }

    /** 查詢借閱請求的持久化狀態，供逾時或結果不確定的呼叫端進行裁決。 */
    @GetMapping("/borrow-requests/{requestId}")
    public LendingDtos.RequestStatusResponse request(@PathVariable String requestId) {
        return service.getRequest(UserContext.require().userId(), parseId(requestId));
    }

    /** 查詢目前使用者所屬的單筆 Loan。 */
    @GetMapping("/loans/{loanId}")
    public LendingDtos.LoanResponse get(@PathVariable String loanId) {
        return service.getLoan(UserContext.require().userId(), parseId(loanId));
    }

    /** 歸還書籍；重複呼叫會回傳第一次歸還後的穩定結果。 */
    @PostMapping("/loans/{loanId}/return")
    public LendingDtos.LoanResponse returnLoan(@PathVariable String loanId) {
        return service.returnLoan(UserContext.require().userId(), parseId(loanId), "MANUAL");
    }

    /** 依 BORROWED 或 RETURNED 狀態查詢目前使用者的借閱，使用簽章游標分頁。 */
    @GetMapping("/users/me/loans")
    public LendingDtos.LoanPage list(
            @RequestParam(defaultValue = "BORROWED") String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return service.list(UserContext.require().userId(), status, cursor, size);
    }

    private static long parseId(String value) {
        try {
            long parsed = Long.parseUnsignedLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ID must be a positive integer");
        }
    }
}
