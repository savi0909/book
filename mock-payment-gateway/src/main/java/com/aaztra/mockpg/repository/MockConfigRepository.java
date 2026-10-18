package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockConfigRepository extends JpaRepository<MockConfig, UUID> {

    Optional<MockConfig> findByConfigKey(String configKey);
}
