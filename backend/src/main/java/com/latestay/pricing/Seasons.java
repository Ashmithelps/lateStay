package com.latestay.pricing;

import com.latestay.hotel.ConflictException;
import com.latestay.hotel.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The season calendar (season_period), edited from /admin. Periods in one town never overlap (a DB constraint). */
@Component
public class Seasons {

    private final JdbcClient jdbc;
    private final Clock clock;

    public Seasons(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<SeasonPeriod> list() {
        return jdbc.sql("SELECT id, town, starts_on, ends_on, multiplier_percent, label FROM season_period "
                        + "ORDER BY town, starts_on")
                .query((rs, n) -> new SeasonPeriod(rs.getObject("id", UUID.class), rs.getString("town"),
                        rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
                        rs.getInt("multiplier_percent"), rs.getString("label")))
                .list();
    }

    public SeasonPeriod create(String town, LocalDate startsOn, LocalDate endsOn, int multiplierPercent,
            String label) {
        if (endsOn.isBefore(startsOn)) {
            throw new ConflictException("The season ends before it starts.");
        }
        SeasonPeriod period = new SeasonPeriod(UUID.randomUUID(), town.strip(), startsOn, endsOn, multiplierPercent,
                label.strip());
        try {
            jdbc.sql("""
                            INSERT INTO season_period (id, town, starts_on, ends_on, multiplier_percent, label,
                                                       created_at)
                            VALUES (:id, :town, :starts, :ends, :percent, :label, :now)""")
                    .param("id", period.id()).param("town", period.town()).param("starts", startsOn)
                    .param("ends", endsOn).param("percent", multiplierPercent).param("label", period.label())
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("That overlaps another season in " + period.town() + ".");
        }
        return period;
    }

    public void delete(UUID id) {
        if (jdbc.sql("DELETE FROM season_period WHERE id = :id").param("id", id).update() == 0) {
            throw new NotFoundException("season", id);
        }
    }

    /** The multiplier for this town's night, 100 outside any season. */
    public int percentFor(String town, LocalDate nightOf) {
        return jdbc.sql("""
                        SELECT multiplier_percent FROM season_period
                        WHERE lower(town) = lower(:town) AND :night BETWEEN starts_on AND ends_on""")
                .param("town", town).param("night", nightOf).query(Integer.class).optional().orElse(100);
    }
}
