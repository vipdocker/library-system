package com.example.library.reminder;

import com.example.library.common.web.PageResponse;
import com.example.library.security.CurrentUser;
import com.example.library.security.LibraryUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications/me")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }
    public record UnreadCount(long unreadCount) {}
    public record ReadAllResult(int updated) {}

    @GetMapping
    public PageResponse<NotificationResponse> inbox(@CurrentUser LibraryUserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.inbox(principal, unreadOnly, page, size);
    }
    @GetMapping("/unread-count")
    public UnreadCount unreadCount(@CurrentUser LibraryUserPrincipal principal) {
        return new UnreadCount(service.unreadCount(principal));
    }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@CurrentUser LibraryUserPrincipal principal, @PathVariable Long id) {
        service.markRead(principal, id);
    }
    @PostMapping("/read-all")
    public ReadAllResult markAllRead(@CurrentUser LibraryUserPrincipal principal) {
        return new ReadAllResult(service.markAllRead(principal));
    }
}
