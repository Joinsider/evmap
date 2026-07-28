package de.joinside.evmap_service.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * One compact, greppable line per boot describing how this instance is configured — the first thing you
 * want to see when a container behaves differently from your local run. Never logs credentials.
 */
@Component
class StartupLogger implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment environment = event.getApplicationContext().getEnvironment();
        String[] profiles = environment.getActiveProfiles();

        log.info("Started {} | profiles={} | logFormat={} | datasource={} | syncEnabled={}",
                environment.getProperty("spring.application.name", "evmap-service"),
                profiles.length == 0 ? "default" : String.join(",", profiles),
                environment.getProperty("logging.structured.format.console", "plain"),
                sanitized(environment.getProperty("spring.datasource.url", "unknown")),
                environment.getProperty("evmap.sync.enabled", "false"));
    }

    /** JDBC URLs may carry credentials in their query string — keep only host and database. */
    private String sanitized(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    }
}
