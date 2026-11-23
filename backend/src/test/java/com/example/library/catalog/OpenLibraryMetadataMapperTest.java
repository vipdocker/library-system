package com.example.library.catalog;

import java.util.ArrayList;
import java.util.List;

import com.example.library.catalog.dto.OpenLibraryLookupResponse.Warning;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenLibraryMetadataMapperTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode json(String text) { return JSON.readTree(text); }
    private static final String ISBN10 = "080442957X";
    private static final String ISBN13 = "9780804429573";

    @Test
    void isbnEquivalenceOnlyConverts978AndNever979() {
        assertThat(OpenLibraryMetadataMapper.equivalent(ISBN10, ISBN10)).isTrue();
        assertThat(OpenLibraryMetadataMapper.equivalent("978-0-8044-2957-3", ISBN10)).isTrue();
        assertThat(OpenLibraryMetadataMapper.equivalent(ISBN10, ISBN13)).isTrue();
        assertThat(OpenLibraryMetadataMapper.equivalent("080442957x", ISBN10)).isTrue();
        assertThat(OpenLibraryMetadataMapper.equivalent("9791234567896", "1234567897")).isFalse();
        assertThat(OpenLibraryMetadataMapper.equivalent("1234567897", "9791234567896")).isFalse();
        assertThat(OpenLibraryMetadataMapper.equivalent("9780306406157", ISBN10)).isFalse();
    }

    @Test
    void editionFieldsAreTrimmedDedupedAndBoundedWithoutTruncation() {
        var warnings = new ArrayList<Warning>();
        var edition = OpenLibraryMetadataMapper.edition(json("""
                {"key":"/books/OL7353617M","isbn_13":["9780804429573"],"title":"  标题 ","publishers":[" 社甲 ","社甲","社乙"],
                 "publish_date":"October 1998","description":{"type":"/type/text","value":" 简介 "},"covers":[-1,8739161,123],
                 "authors":[{"key":"/authors/OL1A"},{"key":"/authors/OL2A"},{"key":"/authors/OL1A"}],"works":[{"key":"/works/OL45804W"}],
                 "notes":"不是简介","by_statement":"不是作者"}"""), ISBN10, "/books/OL7353617M", warnings);
        assertThat(edition.title()).isEqualTo("标题"); assertThat(edition.publisher()).isEqualTo("社甲、社乙");
        assertThat(edition.publishYear()).isEqualTo(1998); assertThat(edition.description()).isEqualTo("简介");
        assertThat(edition.coverId()).isEqualTo(8739161L); assertThat(edition.coversPresent()).isTrue();
        assertThat(edition.authorKeys()).containsExactly("OL1A", "OL2A"); assertThat(edition.workKey()).isEqualTo("OL45804W");
        assertThat(warnings).isEmpty();
        var invalid = new ArrayList<Warning>();
        var bad = OpenLibraryMetadataMapper.edition(json("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"title\":\"" + "x".repeat(201)
                + "\",\"publishers\":[\"" + "y".repeat(101) + "\"],\"publish_date\":\"1998 / 2000\",\"description\":{\"type\":\"/type/html\",\"value\":\"<b>x</b>\"},"
                + "\"covers\":\"bad\",\"authors\":\"bad\",\"works\":[{\"key\":\"/works/OL1W\"},{\"key\":\"/works/OL2W\"}]}"), ISBN10, null, invalid);
        assertThat(bad.title()).isNull(); assertThat(bad.publisher()).isNull(); assertThat(bad.publishYear()).isNull(); assertThat(bad.description()).isNull();
        assertThat(bad.coverId()).isNull(); assertThat(bad.authorKeys()).isEmpty(); assertThat(bad.workKey()).isNull();
        assertThat(invalid).extracting(Warning::field).contains("title", "publisher", "publishYear", "description", "cover", "author");
        assertThat(invalid).extracting(Warning::code).contains("FIELD_TOO_LONG", "YEAR_AMBIGUOUS", "FIELD_INVALID");
        assertThat(OpenLibraryMetadataMapper.edition(json("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"description\":\"" + "d".repeat(2001) + "\"}"),
                ISBN10, null, new ArrayList<>()).description()).isNull();
    }

    @Test
    void equivalenceRequiresValidChecksumsAndDescriptionsNeverApplyHtmlMarkup() {
        assertThat(OpenLibraryMetadataMapper.equivalent("0804429570", ISBN13)).isFalse();
        assertThat(OpenLibraryMetadataMapper.equivalent("9780804429570", ISBN10)).isFalse();
        var warnings = new ArrayList<Warning>();
        var edition = OpenLibraryMetadataMapper.edition(json("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"description\":\"<p>HTML, not plain text</p>\"}"), ISBN10, null, warnings);
        assertThat(edition.description()).isNull();
        assertThat(warnings).extracting(Warning::code).containsExactly("FIELD_INVALID");
    }

    @Test
    void missingOrConflictingKeysAndIsbnsAreMismatchesNotOtherEditionsData() {
        for (String body : new String[]{"{\"isbn_10\":[\"080442957X\"]}", "{\"key\":\"/works/OL1W\",\"isbn_10\":[\"080442957X\"]}",
                "{\"key\":\"/books/OL1M\"}", "{\"key\":\"/books/OL1M\",\"isbn_10\":[]}", "{\"key\":\"/books/OL1M\",\"isbn_13\":[\"9780306406157\"]}",
                "{\"key\":\"/books/OL1M\",\"isbn_10\":[1234]}", "{\"key\":\"/books/OL2M\",\"isbn_10\":[\"080442957X\"]}"}) {
            assertThatThrownBy(() -> OpenLibraryMetadataMapper.edition(json(body), ISBN10, "/books/OL1M", new ArrayList<>()))
                    .as(body).isInstanceOf(OpenLibraryMetadataMapper.EditionMismatch.class);
        }
        assertThatThrownBy(() -> OpenLibraryMetadataMapper.edition(json("[]"), ISBN10, null, new ArrayList<>()))
                .isInstanceOf(OpenLibraryMetadataMapper.InvalidPayload.class);
    }

    @Test
    void coversArePositiveIdsOnlyAndAbsentCoversMeansIsbnFallback() {
        var none = OpenLibraryMetadataMapper.edition(json("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"covers\":[-1,0]}"), ISBN10, null, new ArrayList<>());
        assertThat(none.coverId()).isNull(); assertThat(none.coversPresent()).isTrue();
        var absent = OpenLibraryMetadataMapper.edition(json("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"]}"), ISBN10, null, new ArrayList<>());
        assertThat(absent.coverId()).isNull(); assertThat(absent.coversPresent()).isFalse();
        for (String covers : new String[]{"[\"1\"]", "[1.5]", "[99999999999999999999]", "[{}]", "true"}) {
            var warnings = new ArrayList<Warning>();
            var edition = OpenLibraryMetadataMapper.edition(json("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"covers\":" + covers + "}"), ISBN10, null, warnings);
            assertThat(edition.coverId()).as(covers).isNull(); assertThat(edition.coversPresent()).isTrue();
            assertThat(warnings).extracting(Warning::field).containsExactly("cover");
        }
    }

    @Test
    void searchAuthorsOnlyWhenWorkKeyAndAuthorKeySetAndNameCountAllAgree() {
        var edition = new OpenLibraryMetadataMapper.Edition("/books/OL1M", null, null, null, null, null, true, List.of("OL1A", "OL2A"), "OL1W");
        var warnings = new ArrayList<Warning>();
        assertThat(OpenLibraryMetadataMapper.authors(json("""
                {"numFound":1,"docs":[{"key":"/works/OL1W","author_key":["OL2A","OL1A"],"author_name":[" 乙 ","甲"]}]}"""), edition, warnings)).isEqualTo("乙、甲");
        assertThat(warnings).isEmpty();
        for (String body : new String[]{"{\"docs\":[]}", "{\"docs\":[{\"key\":\"/works/OL9W\",\"author_key\":[\"OL1A\",\"OL2A\"],\"author_name\":[\"甲\",\"乙\"]}]}",
                "{\"docs\":[{\"key\":\"/works/OL1W\",\"author_key\":[\"OL1A\"],\"author_name\":[\"甲\"]}]}",
                "{\"docs\":[{\"key\":\"/works/OL1W\",\"author_key\":[\"OL1A\",\"OL2A\"],\"author_name\":[\"甲\"]}]}",
                "{\"docs\":[{\"key\":\"/works/OL1W\",\"author_key\":[\"OL1A\",\"OL3A\"],\"author_name\":[\"甲\",\"丙\"]}]}",
                "{\"docs\":[{\"key\":\"/works/OL1W\",\"author_key\":[\"OL1A\",\"OL2A\"],\"author_name\":[\"甲\",7]}]}", "[]"}) {
            var unverified = new ArrayList<Warning>();
            assertThat(OpenLibraryMetadataMapper.authors(json(body), edition, unverified)).as(body).isNull();
            assertThat(unverified).extracting(Warning::code).as(body).containsExactly("AUTHOR_UNVERIFIED");
        }
        var tooLong = new ArrayList<Warning>();
        assertThat(OpenLibraryMetadataMapper.authors(json("{\"docs\":[{\"key\":\"/works/OL1W\",\"author_key\":[\"OL1A\",\"OL2A\"],\"author_name\":[\""
                + "a".repeat(60) + "\",\"" + "b".repeat(60) + "\"]}]}"), edition, tooLong)).isNull();
        assertThat(tooLong).extracting(Warning::code).containsExactly("FIELD_TOO_LONG");
    }

    @Test
    void workDescriptionRequiresMatchingKeyAndApprovedTextShapes() {
        var warnings = new ArrayList<Warning>();
        assertThat(OpenLibraryMetadataMapper.workDescription(json("{\"key\":\"/works/OL1W\",\"description\":{\"type\":\"/type/text\",\"value\":\" 作品简介 \"}}"), "OL1W", warnings))
                .isEqualTo("作品简介");
        assertThat(OpenLibraryMetadataMapper.workDescription(json("{\"key\":\"/works/OL1W\",\"description\":\"纯文本\"}"), "OL1W", new ArrayList<>())).isEqualTo("纯文本");
        for (String body : new String[]{"{\"key\":\"/works/OL2W\",\"description\":\"x\"}", "{\"description\":\"x\"}",
                "{\"key\":\"/works/OL1W\",\"description\":{\"value\":\"x\"}}", "{\"key\":\"/works/OL1W\",\"notes\":\"x\"}"}) {
            assertThat(OpenLibraryMetadataMapper.workDescription(json(body), "OL1W", new ArrayList<>())).as(body).isNull();
        }
    }
}
