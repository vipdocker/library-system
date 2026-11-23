package com.example.library.catalog;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * lib.open-library.* (OL-OPT-v1). The application identifies itself with a real version; an optional contact email is
 * sent only to openlibrary.org and never raises the 1 request/second policy. Invalid values fail startup.
 */
@ConfigurationProperties(prefix = "lib.open-library")
@Validated
public class OpenLibraryProperties {
    /** RFC 9110 token characters only: no spaces, parentheses, slashes or control characters (header-injection safe). */
    static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}";
    static final String VERSION = "[0-9A-Za-z.+_-]{1,32}";
    /** Conservative ASCII address; excludes CR/LF/space/parentheses so it can sit inside the User-Agent comment. */
    static final String EMAIL = "|[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+";

    @NotNull
    @Pattern(regexp = TOKEN)
    private String appName = "LibraryCatalog";

    /** Filled from the Maven project version via resource filtering; never a hard-coded guess. */
    @Pattern(regexp = VERSION)
    private String appVersion;

    @NotNull
    @Size(max = 254)
    @Pattern(regexp = EMAIL)
    private String contactEmail = "";

    @NotNull
    private Duration directFailureTtl = Duration.ofSeconds(30);

    @AssertTrue(message = "direct-failure-ttl must be between 0 (disabled) and 60 seconds")
    boolean isDirectFailureTtlValid() {
        return directFailureTtl == null || !directFailureTtl.isNegative() && directFailureTtl.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    /** openlibrary.org only: application name/version plus optional contact as recommended by Open Library. */
    public String metadataUserAgent() {
        String base = assetUserAgent();
        return contactEmail == null || contactEmail.isEmpty() ? base : base + " (" + contactEmail + ")";
    }

    /** covers/archive: no contact information is disclosed. */
    public String assetUserAgent() {
        return appVersion == null ? appName : appName + "/" + appVersion;
    }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public Duration getDirectFailureTtl() { return directFailureTtl; }
    public void setDirectFailureTtl(Duration directFailureTtl) { this.directFailureTtl = directFailureTtl; }
}
