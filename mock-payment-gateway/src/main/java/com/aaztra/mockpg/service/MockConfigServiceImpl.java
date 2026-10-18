package com.aaztra.mockpg.service;

import com.aaztra.mockpg.entity.MockConfig;
import com.aaztra.mockpg.repository.MockConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class MockConfigServiceImpl implements MockConfigService {

    private static final Logger log = LoggerFactory.getLogger(MockConfigServiceImpl.class);

    private final MockConfigRepository configRepository;

    // Default values
    private static final Map<String, Object> DEFAULTS = Map.of(
        "webhook_url", Map.of("url", "http://localhost:8081/api/customer/api/v1/webhooks/payment"),
        "failure_rate", Map.of("percentage", 0),
        "magic_amounts_enabled", Map.of("enabled", true),
        "auto_capture", Map.of("enabled", true),
        "fee_percentage", Map.of("rate", 2.0, "gst_rate", 18.0),
        "webhook_delay_ms", Map.of("min", 100, "max", 500)
    );

    public MockConfigServiceImpl(MockConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getWebhookUrl() {
        return getConfigValue("webhook_url")
            .map(config -> {
                if (config instanceof Map<?, ?> map) {
                    Object url = map.get("url");
                    return url != null ? url.toString() : null;
                }
                return null;
            });
    }

    @Override
    @Transactional(readOnly = true)
    public int getFailureRate() {
        return getConfigValue("failure_rate")
            .map(config -> {
                if (config instanceof Map<?, ?> map) {
                    Object percentage = map.get("percentage");
                    if (percentage instanceof Number number) {
                        return number.intValue();
                    }
                }
                return 0;
            })
            .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMagicAmountsEnabled() {
        return getConfigValue("magic_amounts_enabled")
            .map(config -> {
                if (config instanceof Map<?, ?> map) {
                    Object enabled = map.get("enabled");
                    return Boolean.TRUE.equals(enabled);
                }
                return true;
            })
            .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAutoCaptureEnabled() {
        return getConfigValue("auto_capture")
            .map(config -> {
                if (config instanceof Map<?, ?> map) {
                    Object enabled = map.get("enabled");
                    return Boolean.TRUE.equals(enabled);
                }
                return true;
            })
            .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAllConfig() {
        Map<String, Object> result = new HashMap<>();

        configRepository.findAll().forEach(config ->
            result.put(config.getConfigKey(), config.getConfigValue())
        );

        // Add defaults for missing keys
        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }

        return result;
    }

    @Override
    public void updateConfig(String key, Object value) {
        log.info("Updating config: {} = {}", key, value);

        MockConfig config = configRepository.findByConfigKey(key)
            .orElseGet(() -> {
                MockConfig newConfig = new MockConfig();
                newConfig.setConfigKey(key);
                newConfig.setCreatedAt(Instant.now());
                return newConfig;
            });

        // Wrap primitive values in a Map for JSONB storage
        Map<String, Object> configValue;
        if (value instanceof Map<?, ?> map) {
            configValue = new HashMap<>();
            map.forEach((k, v) -> configValue.put(k.toString(), v));
        } else {
            configValue = Map.of("value", value);
        }

        config.setConfigValue(configValue);
        config.setUpdatedAt(Instant.now());

        configRepository.save(config);
    }

    @Override
    public void resetToDefaults() {
        log.info("Resetting all config to defaults");

        configRepository.deleteAll();

        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            MockConfig config = new MockConfig();
            config.setConfigKey(entry.getKey());

            // Ensure value is a Map for JSONB storage
            if (entry.getValue() instanceof Map<?, ?> map) {
                Map<String, Object> configValue = new HashMap<>();
                map.forEach((k, v) -> configValue.put(k.toString(), v));
                config.setConfigValue(configValue);
            } else {
                config.setConfigValue(Map.of("value", entry.getValue()));
            }

            config.setCreatedAt(Instant.now());
            config.setUpdatedAt(Instant.now());
            configRepository.save(config);
        }
    }

    private Optional<Object> getConfigValue(String key) {
        return configRepository.findByConfigKey(key)
            .map(MockConfig::getConfigValue)
            .map(Object.class::cast)
            .or(() -> Optional.ofNullable(DEFAULTS.get(key)));
    }
}
