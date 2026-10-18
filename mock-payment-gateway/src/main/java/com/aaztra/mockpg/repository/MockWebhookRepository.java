package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockWebhook;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockWebhookRepository extends JpaRepository<MockWebhook, UUID> {

    Optional<MockWebhook> findByEventId(String eventId);

    /**
     * Find webhooks ready for retry (pending status, next retry time passed).
     */
    @Query("SELECT w FROM MockWebhook w WHERE w.status = 'pending' AND w.attempts < w.maxAttempts AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now) ORDER BY w.createdAt ASC")
    List<MockWebhook> findWebhooksReadyForRetry(Instant now);

    /**
     * Find webhooks by status with pagination.
     */
    List<MockWebhook> findByStatus(String status, Pageable pageable);

    /**
     * Find webhooks ready for retry (alternative query for scheduler).
     */
    @Query("SELECT w FROM MockWebhook w WHERE w.status = :status AND w.nextRetryAt <= :now")
    List<MockWebhook> findByStatusAndNextRetryAtBefore(String status, Instant now);

    /**
     * Count webhooks by status.
     */
    long countByStatus(String status);

    /**
     * Delete webhooks created before cutoff date (for cleanup).
     */
    @Modifying
    @Query("DELETE FROM MockWebhook w WHERE w.createdAt < :cutoff")
    int deleteByCreatedAtBefore(Instant cutoff);
}
