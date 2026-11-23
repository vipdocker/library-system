package com.example.library.catalog;

import java.time.Duration;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpenLibraryPropertiesTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<ConstraintViolation<OpenLibraryProperties>> violations(OpenLibraryProperties properties) {
        return VALIDATOR.validate(properties);
    }

    @Test
    void defaultsAreSafeWithoutContactAndUseOneSecondPolicy() {
        var properties = new OpenLibraryProperties();
        assertThat(properties.getAppName()).isEqualTo("LibraryCatalog");
        assertThat(properties.getContactEmail()).isEmpty();
        assertThat(properties.getDirectFailureTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(violations(properties)).isEmpty();
        properties.setAppVersion("0.1.0-SNAPSHOT");
        assertThat(properties.metadataUserAgent()).isEqualTo("LibraryCatalog/0.1.0-SNAPSHOT");
        assertThat(properties.assetUserAgent()).isEqualTo("LibraryCatalog/0.1.0-SNAPSHOT");
    }

    @Test
    void contactIsOnlyInMetadataUserAgentAndNeverRaisesRate() {
        var properties = new OpenLibraryProperties();
        properties.setAppVersion("1.2.3"); properties.setContactEmail("ops.team+ol@example.org");
        assertThat(violations(properties)).isEmpty();
        assertThat(properties.metadataUserAgent()).isEqualTo("LibraryCatalog/1.2.3 (ops.team+ol@example.org)");
        assertThat(properties.assetUserAgent()).isEqualTo("LibraryCatalog/1.2.3").doesNotContain("@");
    }

    @Test
    void headerInjectionAndInvalidValuesAreRejected() {
        for (String name : new String[]{"", "x".repeat(65), "Library Catalog", "Lib\r\nX-Evil: 1", "Lib(x)", "图书馆", "Lib/1"}) {
            var properties = new OpenLibraryProperties(); properties.setAppName(name);
            assertThat(violations(properties)).as(name).isNotEmpty();
        }
        assertThat(violations(withName("x".repeat(64)))).isEmpty();
        for (String email : new String[]{"a@b", "no-at.example.org", "a@b.org\r\nX-Evil: 1", "a b@example.org", "a@b.org)", "(a@b.org",
                "x".repeat(250) + "@b.org", "@example.org", "a@-example.org", "a@example.org\u0000"}) {
            var properties = new OpenLibraryProperties(); properties.setContactEmail(email);
            assertThat(violations(properties)).as(email).isNotEmpty();
        }
        for (String version : new String[]{"@project.version@", "1 2", "1\n2", "x".repeat(33)}) {
            var properties = new OpenLibraryProperties(); properties.setAppVersion(version);
            assertThat(violations(properties)).as(version).isNotEmpty();
        }
    }

    @Test
    void directFailureTtlAllowsZeroToDisableAndAtMostSixtySeconds() {
        for (Duration ok : new Duration[]{Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(60)}) {
            var properties = new OpenLibraryProperties(); properties.setDirectFailureTtl(ok);
            assertThat(violations(properties)).isEmpty();
        }
        for (Duration bad : new Duration[]{Duration.ofSeconds(-1), Duration.ofSeconds(61), Duration.ofMinutes(5), null}) {
            var properties = new OpenLibraryProperties(); properties.setDirectFailureTtl(bad);
            assertThat(violations(properties)).isNotEmpty();
        }
    }

    private static OpenLibraryProperties withName(String name) {
        var properties = new OpenLibraryProperties(); properties.setAppName(name); return properties;
    }
}
