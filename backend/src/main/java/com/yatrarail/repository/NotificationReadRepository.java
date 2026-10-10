package com.yatrarail.repository;

import com.yatrarail.entity.NotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NotificationReadRepository extends JpaRepository<NotificationRead, Long> {

    /**
     * The ids of every announcement this user has already acknowledged.
     * <p>
     * One query rather than one per row: the feed mixes personal notifications with every
     * announcement ever published, and resolving read state row by row would be an N+1 on the single
     * most frequently polled endpoint in the application — the navbar bell refreshes every 30s.
     */
    @Query("select r.notification.id from NotificationRead r where r.user.id = :userId")
    List<Long> findAnnouncementIdsReadBy(@Param("userId") Long userId);

    List<NotificationRead> findByUserId(Long userId);

    boolean existsByNotificationIdAndUserId(Long notificationId, Long userId);

    long countByNotificationId(Long notificationId);

    List<NotificationRead> findByNotificationIdIn(Collection<Long> notificationIds);

    void deleteByNotificationIdIn(Collection<Long> notificationIds);
}