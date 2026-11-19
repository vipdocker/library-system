package com.example.library.loan;

import com.example.library.common.web.PageResponse;
import com.example.library.loan.dto.BorrowRequest;
import com.example.library.loan.dto.LoanResponse;
import com.example.library.loan.dto.ReturnByBarcodeRequest;
import com.example.library.security.CurrentUser;
import com.example.library.security.LibraryUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 借阅接口（design-v0.1 第 8.4 节）。
 * 授权由 SecurityConfig URL 规则保证：借/还/列表仅 ADMIN；续借按 D2 开关；/me 仅 READER（AC-AUTH-04）。
 * 经办人/调用者身份只从 principal 取得（防 IDOR，第 7 节）。
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    /** 201 Loan；404 READER_NOT_FOUND/COPY_NOT_FOUND；409 见第 9.2 节。 */
    @PostMapping
    public ResponseEntity<LoanResponse> borrow(@Valid @RequestBody BorrowRequest request,
                                               @CurrentUser LibraryUserPrincipal principal) {
        LoanResponse response = loanService.borrow(request.readerCardNo(), request.copyBarcode(),
                principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** 200 Loan（status=RETURNED）；404 LOAN_NOT_FOUND；409 LOAN_ALREADY_RETURNED。 */
    @PostMapping("/{id}/return")
    public LoanResponse returnLoan(@PathVariable Long id, @CurrentUser LibraryUserPrincipal principal) {
        return loanService.returnLoan(id, principal.getUserId());
    }

    /** 200 Loan；404 COPY_NOT_FOUND/ACTIVE_LOAN_NOT_FOUND。 */
    @PostMapping("/return-by-barcode")
    public LoanResponse returnByBarcode(@Valid @RequestBody ReturnByBarcodeRequest request,
                                        @CurrentUser LibraryUserPrincipal principal) {
        return loanService.returnByBarcode(request.copyBarcode(), principal.getUserId());
    }

    /** 200 Loan；403（读者且 D2=否，URL 规则）；404；409 见第 9.4 节。 */
    @PostMapping("/{id}/renew")
    public LoanResponse renew(@PathVariable Long id, @CurrentUser LibraryUserPrincipal principal) {
        return loanService.renew(id, principal);
    }

    /** 200 Page<Loan>；readerId/status/copyBarcode/q/page/size/sort（白名单 borrowDate、dueDate）。 */
    @GetMapping
    public PageResponse<LoanResponse> search(
            @RequestParam(required = false) Long readerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String copyBarcode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return loanService.search(readerId, status, copyBarcode, q, page, size, sort);
    }

    /**
     * 200 Page<Loan>（只含本人，AC-AUTH-05）；status/page/size。
     * 不声明 readerId 参数：读者身份只从 principal 取得，传了也被忽略。
     */
    @GetMapping("/me")
    public PageResponse<LoanResponse> me(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @CurrentUser LibraryUserPrincipal principal) {
        return loanService.myLoans(principal, status, page, size);
    }
}