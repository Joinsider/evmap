package de.joinside.evmap_service.support;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * One migrated PostGIS database per test JVM, for the SQL that H2 cannot run.
 * <p>
 * The spatial queries use {@code geography} and {@code ST_DWithin}, the ingestion relies on
 * PostgreSQL upsert semantics, and the schema itself is Liquibase-managed PostgreSQL — none of that
 * exists in H2, and a mocked {@code JdbcClient} would test the mock. So these tests run against the
 * same image Compose deploys and against the real changelog.
 * <p>
 * Started once and shared: a container per class would cost seconds each for no isolation gain,
 * since every test clears the tables it writes ({@link #clearMasterData()}).
 * <p>
 * Without a container runtime the tests are skipped locally — but not on CI, where a silently skipped
 * suite would read as green and quietly drop the coverage these tests exist for.
 */
public final class PostgisDatabase {
    /**
     * What Compose deploys, and the practical choice besides: {@code postgis/postgis} publishes no
     * arm64 image and does not start on Apple Silicon.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("kartoza/postgis:17-3.5");
    private static final String NAME = "evmap";

    private static DataSource dataSource;
    private static RuntimeException startupFailure;

    private PostgisDatabase() {
    }

    /** The migrated database, started on first use. Skips the calling test when no runtime exists. */
    public static synchronized DataSource dataSource() {
        if (dataSource != null) return dataSource;
        // A container that failed to start fails every later test at once, not after another timeout.
        if (startupFailure != null) throw startupFailure;

        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && System.getenv("CI") != null)
            throw new IllegalStateException("No container runtime on CI — the PostGIS tests must not be skipped here");
        Assumptions.assumeTrue(available, "No container runtime available; PostGIS tests skipped");

        try {
            dataSource = start();
            return dataSource;
        } catch (RuntimeException e) {
            startupFailure = e;
            throw e;
        }
    }

    public static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** Removes every master-data row, so each test starts from an empty register. */
    public static void clearMasterData() {
        jdbc().sql("TRUNCATE master.charging_connector, master.charge_point, master.station_source, "
                + "master.charging_station, master.sync_run, master.source_sync_state CASCADE").update();
    }

    /**
     * Seeds one station directly, for tests of the read side that live outside {@code sync} and so
     * cannot use its ingestion. {@code location} is a generated column and follows the coordinates.
     */
    public static UUID insertStation(String name, String operator, String countryCode, double latitude, double longitude) {
        UUID id = UUID.randomUUID();
        jdbc().sql("INSERT INTO master.charging_station (id, display_name, street, city, postal_code, country_code, "
                        + "operator_name, latitude, longitude, availability_status) "
                        + "VALUES (:id, :name, 'Hauptstraße 1', 'Stuttgart', '70173', :country, :operator, :lat, :lon, 'OPERATIONAL')")
                .param("id", id).param("name", name).param("country", countryCode).param("operator", operator)
                .param("lat", latitude).param("lon", longitude)
                .update();
        return id;
    }

    /** A charge point of {@code stationId}; {@code evseId} may be {@code null}, as in 70 % of the register. */
    public static UUID insertChargePoint(UUID stationId, String sourceChargePointId, String evseId) {
        UUID id = UUID.randomUUID();
        jdbc().sql("INSERT INTO master.charge_point (id, station_id, source, source_charge_point_id, evse_id, evse_id_normalized) "
                        + "VALUES (:id, :station, 'TEST', :sourceId, :evse, :normalized)")
                .param("id", id).param("station", stationId).param("sourceId", sourceChargePointId)
                .param("evse", evseId)
                .param("normalized", evseId == null ? null : evseId.toUpperCase().replaceAll("[^A-Z0-9]", ""))
                .update();
        return id;
    }

    public static void insertConnector(UUID stationId, String type, BigDecimal powerKw, int quantity) {
        jdbc().sql("INSERT INTO master.charging_connector (id, station_id, connector_type, power_kw, quantity) "
                        + "VALUES (:id, :station, :type, :power, :quantity)")
                .param("id", UUID.randomUUID()).param("station", stationId).param("type", type)
                .param("power", powerKw).param("quantity", quantity)
                .update();
    }

    /**
     * Kartoza initialises, shuts down and restarts in the foreground, logging "ready to accept
     * connections" once per start; only the second one is reachable from outside. Its own entrypoint
     * is kept — overriding the command, as the PostgreSQL module does, makes it exit immediately.
     */
    @SuppressWarnings("resource") // Lives for the JVM; Testcontainers' reaper removes it afterwards.
    private static DataSource start() {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withExposedPorts(5432)
                .withEnv("POSTGRES_DB", NAME)
                .withEnv("POSTGRES_DBNAME", NAME)
                .withEnv("POSTGRES_USER", NAME)
                .withEnv("POSTGRES_PASSWORD", NAME)
                .withEnv("POSTGRES_PASS", NAME)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        container.start();

        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl("jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + NAME);
        source.setUser(NAME);
        source.setPassword(NAME);
        migrate(source);
        return source;
    }

    private static void migrate(DataSource source) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(source);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        try {
            liquibase.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Liquibase migration failed", e);
        }
    }
}
