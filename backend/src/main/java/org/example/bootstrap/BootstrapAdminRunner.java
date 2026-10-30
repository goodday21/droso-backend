package org.example.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs {@link BootstrapAdminService} once the application has started (after the migrations of
 * Flyway). An exception here stops the start-up: an installation whose configured administrator
 * cannot be created must not run as if it had one.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private final BootstrapAdminService service;
    private final BootstrapAdminProperties properties;

    public BootstrapAdminRunner(BootstrapAdminService service, BootstrapAdminProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.bootstrap(properties);
    }
}
