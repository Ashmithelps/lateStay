package com.latestay;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A new, empty database in the shared PostGIS container, migrated by Flyway: for tests that need exact totals
 * (nothing else writes there) or several app instances on one database.
 */
public final class FreshDatabase {

    private static final AtomicInteger NEXT = new AtomicInteger();

    public final String url;
    public final String username;
    public final String password;
    public final DataSource dataSource;
    public final JdbcClient jdbc;

    private FreshDatabase(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.dataSource = new DriverManagerDataSource(url, username, password);
        this.jdbc = JdbcClient.create(dataSource);
    }

    public static FreshDatabase create(String prefix) {
        var container = TestcontainersConfiguration.POSTGIS;
        if (!container.isRunning()) {
            container.start();
        }
        String name = prefix + "_" + ProcessHandle.current().pid() + "_" + NEXT.incrementAndGet();
        JdbcClient.create(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(),
                container.getPassword())).sql("CREATE DATABASE " + name).update();
        String url = container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        FreshDatabase db = new FreshDatabase(url, container.getUsername(), container.getPassword());
        Flyway.configure().dataSource(db.dataSource).locations("classpath:db/migration").load().migrate();
        return db;
    }
}
