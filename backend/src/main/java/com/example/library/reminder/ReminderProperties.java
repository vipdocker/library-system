package com.example.library.reminder;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("lib.reminder")
@Validated
public class ReminderProperties {
    private boolean enabled = true;
    private boolean scanOnStartup = true;
    @Min(1) private int dueSoonDays = 3;
    @Min(1) private int overdueRepeatDays = 7;
    @NotNull private Duration scanInterval = Duration.ofHours(1);

    @AssertTrue(message = "scan-interval must be positive and representable in milliseconds")
    public boolean isScanIntervalValid() {
        try { return scanInterval != null && scanInterval.toMillis() > 0; }
        catch (ArithmeticException ex) { return false; }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isScanOnStartup() { return scanOnStartup; }
    public void setScanOnStartup(boolean scanOnStartup) { this.scanOnStartup = scanOnStartup; }
    public int getDueSoonDays() { return dueSoonDays; }
    public void setDueSoonDays(int dueSoonDays) { this.dueSoonDays = dueSoonDays; }
    public int getOverdueRepeatDays() { return overdueRepeatDays; }
    public void setOverdueRepeatDays(int overdueRepeatDays) { this.overdueRepeatDays = overdueRepeatDays; }
    public Duration getScanInterval() { return scanInterval; }
    public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval; }
}
