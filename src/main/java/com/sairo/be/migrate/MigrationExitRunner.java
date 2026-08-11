package com.sairo.be.migrate;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Flyway migration runs during context startup (before any ApplicationRunner),
// so reaching run() here means migration already succeeded. Explicitly exits 0
// instead of relying on the JVM shutting itself down once main() returns.
@Component
@Profile("migrate")
public class MigrationExitRunner implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    public MigrationExitRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
