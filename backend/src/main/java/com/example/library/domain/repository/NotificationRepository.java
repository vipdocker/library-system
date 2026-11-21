package com.example.library.domain.repository;

import com.example.library.domain.entity.Notification;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @EntityGraph(attributePaths = {"loan", "loan.copy", "loan.copy.book"})
    @Query("select n from Notification n where n.readerId = :readerId and (:unreadOnly = false or n.readAt is null)")
    Page<Notification> inbox(@Param("readerId") Long readerId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);
    long countByReaderIdAndReadAtIsNull(Long readerId);

    @Modifying
    @Query("update Notification n set n.readAt = coalesce(n.readAt, :now) where n.id = :id and n.readerId = :readerId")
    int markRead(@Param("id") Long id, @Param("readerId") Long readerId, @Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.readerId = :readerId and n.readAt is null")
    int markAllRead(@Param("readerId") Long readerId, @Param("now") Instant now);
}
