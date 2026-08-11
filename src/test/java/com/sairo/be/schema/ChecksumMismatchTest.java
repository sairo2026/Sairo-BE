package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ChecksumMismatchTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private Path migrationDir;

    @BeforeEach
    void copyMigrationsToTempDir() throws IOException {
        migrationDir = Files.createTempDirectory("sairo-flyway-checksum-test");
        Path source = Path.of("src/main/resources/db/migration");
        for (String fileName : new String[]{
                "V1__init.sql",
                "V2__create_member_and_office_schema.sql",
                "V3__create_property_and_coordination_schema.sql",
                "V4__create_expiry_task_and_shedlock.sql"
        }) {
            Files.copy(source.resolve(fileName), migrationDir.resolve(fileName));
        }
    }

    @AfterEach
    void cleanupTempDir() throws IOException {
        try (var walk = Files.walk(migrationDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private Flyway flywayFor(Path location) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + location)
                .load();
    }

    @Test
    void 이미_적용된_마이그레이션_파일을_바꾸면_validate가_실패한다() throws IOException {
        Flyway flyway = flywayFor(migrationDir);
        flyway.migrate();

        Path v2 = migrationDir.resolve("V2__create_member_and_office_schema.sql");
        String original = Files.readString(v2, StandardCharsets.UTF_8);
        Files.writeString(v2, original + "\n-- 체크섬을 깨뜨리기 위한 사후 변경\n", StandardCharsets.UTF_8);

        Flyway flywayAfterEdit = flywayFor(migrationDir);
        assertThatThrownBy(flywayAfterEdit::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void 파일을_바꾸지_않으면_validate는_그대로_통과한다() {
        Flyway flyway = flywayFor(migrationDir);
        flyway.migrate();

        Flyway flywayAgain = flywayFor(migrationDir);
        assertThatCode(flywayAgain::validate).doesNotThrowAnyException();
    }
}
