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

@Validated
@RestController
@RequestMapping("/api/v1")
public class LoanController {
    private final LendingService service;

    public LoanController(LendingService service) {
        this.service = service;
    }

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

    @GetMapping("/borrow-requests/{requestId}")
    public LendingDtos.RequestStatusResponse request(@PathVariable String requestId) {
        return service.getRequest(UserContext.require().userId(), parseId(requestId));
    }

    @GetMapping("/loans/{loanId}")
    public LendingDtos.LoanResponse get(@PathVariable String loanId) {
        return service.getLoan(UserContext.require().userId(), parseId(loanId));
    }

    @PostMapping("/loans/{loanId}/return")
    public LendingDtos.LoanResponse returnLoan(@PathVariable String loanId) {
        return service.returnLoan(UserContext.require().userId(), parseId(loanId), "MANUAL");
    }

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
