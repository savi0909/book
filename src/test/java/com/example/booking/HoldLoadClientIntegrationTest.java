package com.example.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "lab.maintenance-enabled=false"})
class HoldLoadClientIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;

    @Test void actualNodeClientDiscoversCommittedResponseLossInBothPolicies() throws Exception {
        // Small contract checks against a disposable test database, never the running lab stack.
        Path log = Path.of("target", "hold-load-client-contract.log");
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder("node", "load-test/test/postgres-contract.mjs", Integer.toString(port))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Node client completed within 45s; inspect %s", log).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    }
}
