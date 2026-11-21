package com.example.library.reminder;

import java.time.Clock;
import java.time.Instant;
import com.example.library.loan.LoanPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReminderScanner {
    private static final Logger log = LoggerFactory.getLogger(ReminderScanner.class);
    private final JdbcTemplate jdbc;
    private final ReminderWriter writer;
    private final ReminderProperties properties;
    private final LoanPolicy policy;
    private final Clock clock;

    public ReminderScanner(JdbcTemplate jdbc, ReminderWriter writer, ReminderProperties properties, LoanPolicy policy, Clock clock) {
        this.jdbc = jdbc; this.writer = writer; this.properties = properties; this.policy = policy; this.clock = clock;
    }

    /** No encompassing transaction: each writer call commits independently, failures propagate. */
    @Transactional(propagation = Propagation.NEVER)
    public int scan() {
        var today = policy.today();
        Instant now = clock.instant();
        var ids = jdbc.queryForList("SELECT id FROM loan WHERE returned_at IS NULL AND due_date <= ? ORDER BY id",
                Long.class, today.plusDays(properties.getDueSoonDays()).toString());
        int inserted = 0;
        for (Long id : ids) { inserted += writer.scanLoan(id, today, now); }
        log.info("reminder.scan date={} candidates={} inserted={}", today, ids.size(), inserted);
        return inserted;
    }
}
