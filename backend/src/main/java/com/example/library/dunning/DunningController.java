package com.example.library.dunning;

import com.example.library.common.web.PageResponse;
import com.example.library.security.CurrentUser;
import com.example.library.security.LibraryUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DunningController {
    private final DunningService service;

    public DunningController(DunningService service) { this.service = service; }

    @PostMapping("/loans/{id}/dunning-records")
    public ResponseEntity<DunningRecordResponse> register(@PathVariable Long id,
            @Valid @RequestBody DunningRequest request, @CurrentUser LibraryUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(id, request, principal));
    }

    @GetMapping("/loans/{id}/dunning-records")
    public DunningCaseResponse history(@PathVariable Long id) { return service.history(id); }

    @GetMapping("/dunning-cases")
    public PageResponse<DunningCaseSummary> cases(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return service.cases(status, page, size, sort);
    }
}
