package com.example.library.reader;

import com.example.library.common.web.PageResponse;
import com.example.library.reader.dto.ReaderCreateRequest;
import com.example.library.reader.dto.ReaderPasswordResetRequest;
import com.example.library.reader.dto.ReaderResponse;
import com.example.library.reader.dto.ReaderUpdateRequest;
import com.example.library.security.CurrentUser;
import com.example.library.security.LibraryUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者接口（design-v0.1 第 8.4 节）。
 * 授权由 SecurityConfig URL 规则保证：GET /api/readers/me 仅 READER；其余 /api/readers/** 仅 ADMIN
 * （AC-AUTH-04）。/me 不声明 readerId 参数，读者身份只从 principal 取得（防 IDOR，AC-AUTH-05）。
 */
@RestController
@RequestMapping("/api/readers")
public class ReaderController {

    private final ReaderService readerService;

    public ReaderController(ReaderService readerService) {
        this.readerService = readerService;
    }

    /** 200 Page<Reader>；q/status/page/size/sort（白名单 name、cardNo、createdAt）。 */
    @GetMapping
    public PageResponse<ReaderResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return readerService.search(q, status, page, size, sort);
    }

    /** 200 Reader（READER 本人）；档案缺失 → 404 READER_PROFILE_NOT_FOUND。 */
    @GetMapping("/me")
    public ReaderResponse me(@CurrentUser LibraryUserPrincipal principal) {
        return readerService.me(principal);
    }

    /** 200 Reader。 */
    @GetMapping("/{id}")
    public ReaderResponse detail(@PathVariable Long id) {
        return readerService.detail(id);
    }

    /** 201 Reader（同时创建 READER 账号，卡号系统生成）。 */
    @PostMapping
    public ResponseEntity<ReaderResponse> create(@Valid @RequestBody ReaderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(readerService.create(request));
    }

    /** 200 Reader（停用时同一事务禁用账号）。 */
    @PutMapping("/{id}")
    public ReaderResponse update(@PathVariable Long id, @Valid @RequestBody ReaderUpdateRequest request) {
        return readerService.update(id, request);
    }

    /** 204（管理员重置口令）。 */
    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody ReaderPasswordResetRequest request) {
        readerService.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }

    /** 204（同时删除账号）；有在借/历史借阅 → 409。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        readerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}