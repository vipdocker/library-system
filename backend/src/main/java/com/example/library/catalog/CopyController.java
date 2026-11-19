package com.example.library.catalog;

import com.example.library.catalog.dto.CopyResponse;
import com.example.library.catalog.dto.CopyStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 副本接口（design-v0.1 第 8.4 节）：PATCH /api/copies/{id}、DELETE /api/copies/{id}。
 * 授权由 SecurityConfig URL 规则保证：仅 ADMIN（AC-AUTH-04）。
 */
@RestController
@RequestMapping("/api/copies")
public class CopyController {

    private final CopyService copyService;

    public CopyController(CopyService copyService) {
        this.copyService = copyService;
    }

    /** 200 Copy；status 只能为 AVAILABLE 或 UNAVAILABLE。 */
    @PatchMapping("/{id}")
    public CopyResponse updateStatus(@PathVariable Long id, @Valid @RequestBody CopyStatusRequest request) {
        return copyService.updateStatus(id, request.status());
    }

    /** 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        copyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}