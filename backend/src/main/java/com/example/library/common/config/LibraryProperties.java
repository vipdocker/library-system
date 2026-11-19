package com.example.library.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * lib.* 配置（design-v0.1 第 5 节；阈值来自用户决定 D1/D2/D5）。
 * 配置非法时启动失败（@Validated）。时区合法性由 ClockConfig 的 ZoneId.of 兜底校验。
 */
@ConfigurationProperties(prefix = "lib")
@Validated
public class LibraryProperties {

    @NotBlank
    private String timezone = "Asia/Shanghai";

    @Valid
    private final Loan loan = new Loan();

    @Valid
    private final Admin admin = new Admin();

    @Valid
    private final Seed seed = new Seed();

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Loan getLoan() {
        return loan;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Seed getSeed() {
        return seed;
    }

    public static class Loan {
        @Min(1)
        private int maxActiveLoans = 5;
        @Min(1)
        private int loanDays = 30;
        /** 可以为 0（不允许续借）。 */
        @Min(0)
        private int maxRenewals = 1;
        @Min(1)
        private int renewDays = 30;
        /** D2：默认只有管理员可续借。 */
        private boolean readerSelfRenew = false;

        public int getMaxActiveLoans() {
            return maxActiveLoans;
        }

        public void setMaxActiveLoans(int maxActiveLoans) {
            this.maxActiveLoans = maxActiveLoans;
        }

        public int getLoanDays() {
            return loanDays;
        }

        public void setLoanDays(int loanDays) {
            this.loanDays = loanDays;
        }

        public int getMaxRenewals() {
            return maxRenewals;
        }

        public void setMaxRenewals(int maxRenewals) {
            this.maxRenewals = maxRenewals;
        }

        public int getRenewDays() {
            return renewDays;
        }

        public void setRenewDays(int renewDays) {
            this.renewDays = renewDays;
        }

        public boolean isReaderSelfRenew() {
            return readerSelfRenew;
        }

        public void setReaderSelfRenew(boolean readerSelfRenew) {
            this.readerSelfRenew = readerSelfRenew;
        }
    }

    public static class Admin {
        @NotBlank
        private String username = "admin";
        /** 来自 LIB_ADMIN_PASSWORD；为空时 dev 用开发默认口令并 WARN，非 dev 启动失败（AC-AUTH-11）。 */
        private String initialPassword = "";
        /** 测试 profile 关闭（application-test.yml）。 */
        private boolean bootstrap = true;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getInitialPassword() {
            return initialPassword;
        }

        public void setInitialPassword(String initialPassword) {
            this.initialPassword = initialPassword;
        }

        public boolean isBootstrap() {
            return bootstrap;
        }

        public void setBootstrap(boolean bootstrap) {
            this.bootstrap = bootstrap;
        }
    }

    public static class Seed {
        private boolean demo = false;

        public boolean isDemo() {
            return demo;
        }

        public void setDemo(boolean demo) {
            this.demo = demo;
        }
    }
}