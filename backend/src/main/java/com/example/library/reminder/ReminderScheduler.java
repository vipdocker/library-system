package com.example.library.reminder;

import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ready event follows all ApplicationRunners (including committed demo data). */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "lib.reminder.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private final ReminderScanner scanner;
    private final ReminderProperties properties;
    private volatile boolean ready;
    public ReminderScheduler(ReminderScanner scanner, ReminderProperties properties) {
        this.scanner = scanner; this.properties = properties;
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady(ApplicationReadyEvent event) {
        ready = true;
        if (properties.isScanOnStartup()) runScan();
    }
    @Scheduled(fixedDelayString = "${lib.reminder.scan-interval:PT1H}", initialDelayString = "${lib.reminder.scan-interval:PT1H}")
    public synchronized void tick() {
        if (ready) runScan();
    }
    private void runScan() {
        try { scanner.scan(); }
        catch (RuntimeException ex) {
            if (isBusy(ex)) {
                log.warn("reminder.scan.busy retry-next-scan", ex);
            } else {
                log.error("reminder.scan.failed", ex);
                throw ex;
            }
        }
    }
    private boolean isBusy(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException) return true;
            if (t instanceof SQLException sql && ((sql.getErrorCode() & 255) == 5 || (sql.getErrorCode() & 255) == 6)) return true;
        }
        return false;
    }
}
