package com.yatrarail.repository;

import com.yatrarail.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Announcements are the rows with no recipient: one authored notice, not one copy per reader. */
    List<Notification> findByUserIsNullOrderByCreatedAtDesc();

    long countByUserIdAndIsReadFalse(Long userId);

    /**
     * Announcements this user has <b>not</b> acknowledged yet.
     * <p>
     * The unread test is "no receipt exists", not "the shared {@code is_read} column is false" — see
     * {@link com.yatrarail.entity.NotificationRead} for why a broadcast cannot own a read flag.
     * Expressed as {@code not exists} rather than a join so a user with no receipts at all still
     * gets every announcement back, and so the whole inbox costs one query.
     */
    @Query("select n from Notification n where n.user is null and not exists "
            + "(select r from NotificationRead r where r.notification.id = n.id and r.user.id = :userId)")
    List<Notification> findUnreadAnnouncementsFor(@Param("userId") Long userId);

    @Query("select count(n) from Notification n where n.user is null and not exists "
            + "(select r from NotificationRead r where r.notification.id = n.id and r.user.id = :userId)")
    long countUnreadAnnouncementsFor(@Param("userId") Long userId);

    /**
     * Marks every unread personal notification read in one statement.
     * <p>
     * The previous implementation loaded each row, flipped a field and saved it back — one select
     * and one update per notification, for an endpoint the "Mark all read" button calls on a
     * passenger who may have a hundred of them. This is the same result in a single round trip.
     */
    @Modifying
    @Query("update Notification n set n.isRead = true where n.user.id = :userId and n.isRead = false")
    int markAllPersonalAsRead(@Param("userId") Long userId);
}