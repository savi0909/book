package com.aaztra.mockpg.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mock Config entity - runtime behavior settings for mock processor
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_config", schema = "payment_processor")
@Getter
@Setter
public class MockConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_value", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configValue;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
