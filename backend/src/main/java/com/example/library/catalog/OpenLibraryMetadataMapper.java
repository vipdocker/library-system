package com.example.library.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.library.catalog.dto.OpenLibraryLookupResponse.Warning;
import com.example.library.common.util.IsbnUtils;
import tools.jackson.databind.JsonNode;

/**
 * Pure Edition/Search/Work mapping (OL-OPT-v1). Only the exact Edition for the requested ISBN may supply fields:
 * a missing or conflicting key/ISBN is a mismatch and no field or cover of another edition is used. Authors come only
 * from a Search document whose work key, author-key set and name count all agree with the Edition; descriptions only
 * from Edition or the single verified Work (string or {type:"/type/text", value}). Over-long values become null plus a
 * warning and are never truncated; notes/HTML/by_statement are never used.
 */
final class OpenLibraryMetadataMapper {
    /** Part of every metadata cache key; bump whenever mapping rules change. */
    static final String VERSION = "edition-v1";
    static final Pattern EDITION_KEY = Pattern.compile("/books/(OL[1-9][0-9]{0,9}M)");
    static final Pattern WORK_KEY = Pattern.compile("/works/(OL[1-9][0-9]{0,9}W)");
    static final Pattern AUTHOR_KEY = Pattern.compile("/authors/(OL[1-9][0-9]{0,9}A)");
    private static final Pattern AUTHOR_ID = Pattern.compile("OL[1-9][0-9]{0,9}A");
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{L}\\p{N}])([1-9][0-9]{3})(?![\\p{L}\\p{N}])");
    private static final int YEAR_SOURCE_LIMIT = 256 * 1024;

    private OpenLibraryMetadataMapper() { }

    /** The edition does not belong to the requested ISBN (or has no verifiable key): use nothing from it. */
    static final class EditionMismatch extends RuntimeException {
        EditionMismatch() { super("EDITION_MISMATCH", null, false, false); }
    }
    /** Structurally invalid payload (not an object, etc.). */
    static final class InvalidPayload extends RuntimeException {
        InvalidPayload() { super("INVALID_JSON", null, false, false); }
    }

    /** coversPresent=false means the Edition has no covers field at all, so the fixed ISBN cover URL may be tried. */
    record Edition(String key, String title, String publisher, String description, Integer publishYear, Long coverId,
                   boolean coversPresent, List<String> authorKeys, String workKey) {
        Edition { authorKeys = List.copyOf(authorKeys); }
    }

    static Edition edition(JsonNode root, String isbn, String expectedKey, List<Warning> warnings) {
        if (root == null || !root.isObject()) { throw new InvalidPayload(); }
        JsonNode key = root.get("key");
        if (key == null || !key.isString() || !EDITION_KEY.matcher(key.asString()).matches()
                || expectedKey != null && !expectedKey.equals(key.asString())) {
            throw new EditionMismatch();
        }
        if (!isbnMatches(root, isbn)) { throw new EditionMismatch(); }
        String title = text(root.get("title"), "title", 200, warnings);
        String publisher = strings(root.get("publishers"), "publisher", 100, warnings);
        Integer year = year(root.get("publish_date"), warnings);
        String description = description(root.get("description"), warnings);
        JsonNode coversNode = root.get("covers");
        boolean coversPresent = coversNode != null && !coversNode.isNull();
        Long coverId = coversPresent ? coverId(coversNode, warnings) : null;
        List<String> authors = authorKeys(root.get("authors"), warnings);
        return new Edition(key.asString(), title, publisher, description, year, coverId, coversPresent, authors, workKey(root.get("works")));
    }

    /** Requested ISBN equals an Edition isbn_10/isbn_13 value, or its 978 counterpart; 979 has no ISBN-10 form. */
    static boolean equivalent(String candidate, String isbn) {
        String a = IsbnUtils.normalize(candidate);
        String b = IsbnUtils.normalize(isbn);
        if (a == null || b == null || !IsbnUtils.isValid(a) || !IsbnUtils.isValid(b)) { return false; }
        if (a.equals(b)) { return true; }
        String a13 = a.length() == 10 ? to13(a) : a;
        String b13 = b.length() == 10 ? to13(b) : b;
        return a13.startsWith("978") && a13.equals(b13);
    }

    private static String to13(String isbn10) {
        String body = "978" + isbn10.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) { sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3); }
        return body + ((10 - sum % 10) % 10);
    }

    private static boolean isbnMatches(JsonNode root, String isbn) {
        for (String field : new String[]{"isbn_10", "isbn_13"}) {
            JsonNode values = root.get(field);
            if (values == null || values.isNull()) { continue; }
            if (!values.isArray()) { return false; }
            for (JsonNode value : values) {
                if (!value.isString()) { return false; }
                if (equivalent(value.asString(), isbn)) { return true; }
            }
        }
        return false;
    }

    /** Verified authors from search.json?q=isbn:..&fields=key,author_key,author_name&limit=1; otherwise null + warning. */
    static String authors(JsonNode root, Edition edition, List<Warning> warnings) {
        JsonNode docs = root != null && root.isObject() ? root.get("docs") : null;
        JsonNode doc = docs != null && docs.isArray() && docs.size() >= 1 ? docs.get(0) : null;
        if (doc == null || !doc.isObject() || edition.workKey() == null) { return unverified(warnings); }
        JsonNode key = doc.get("key");
        JsonNode keys = doc.get("author_key");
        JsonNode names = doc.get("author_name");
        if (key == null || !key.isString() || !("/works/" + edition.workKey()).equals(key.asString())
                || keys == null || !keys.isArray() || names == null || !names.isArray() || keys.size() != names.size()) {
            return unverified(warnings);
        }
        var seen = new LinkedHashSet<String>();
        var result = new LinkedHashSet<String>();
        for (int i = 0; i < keys.size(); i++) {
            JsonNode id = keys.get(i);
            JsonNode name = names.get(i);
            if (!id.isString() || !AUTHOR_ID.matcher(id.asString()).matches() || !seen.add(id.asString())
                    || !name.isString() || name.asString().trim().isEmpty()) {
                return unverified(warnings);
            }
            result.add(name.asString().trim());
        }
        if (!seen.equals(new LinkedHashSet<>(edition.authorKeys()))) {
            return unverified(warnings);
        }
        String joined = String.join("、", result);
        if (joined.length() > 100) {
            warnings.add(new Warning("FIELD_TOO_LONG", "author", "上游作者列表过长，未截断或应用")); return null;
        }
        return joined;
    }

    private static String unverified(List<Warning> warnings) {
        warnings.add(new Warning("AUTHOR_UNVERIFIED", "author", "无法核对作者与本版本一致，未采用作者，请手工录入"));
        return null;
    }

    /** Work-level description only when the Work key matches the Edition's single work. */
    static String workDescription(JsonNode root, String workKey, List<Warning> warnings) {
        if (root == null || !root.isObject()) { return null; }
        JsonNode key = root.get("key");
        if (key == null || !key.isString() || !("/works/" + workKey).equals(key.asString())) { return null; }
        return description(root.get("description"), warnings);
    }

    private static String description(JsonNode value, List<Warning> warnings) {
        if (value == null || value.isNull()) { return null; }
        JsonNode text = value;
        if (value.isObject()) {
            JsonNode type = value.get("type");
            text = type != null && type.isString() && "/type/text".equals(type.asString()) ? value.get("value") : null;
            if (text == null || !text.isString()) {
                warnings.add(new Warning("FIELD_INVALID", "description", "上游简介格式不在许可范围，保留人工录入")); return null;
            }
        }
        String plain = text(text, "description", 2000, warnings);
        if (plain != null && Pattern.compile("(?is)<\\s*/?[a-z][^>]*>").matcher(plain).find()) {
            warnings.add(new Warning("FIELD_INVALID", "description", "上游简介包含 HTML，未采用，请手工录入"));
            return null;
        }
        return plain;
    }

    static String text(JsonNode value, String field, int max, List<Warning> warnings) {
        if (value == null || value.isNull()) { return null; }
        if (!value.isString()) {
            warnings.add(new Warning("FIELD_INVALID", field, "上游字段类型不合法，保留人工录入")); return null;
        }
        String result = value.asString().trim();
        if (result.length() > max) {
            warnings.add(new Warning("FIELD_TOO_LONG", field, "上游字段过长，未截断或应用")); return null;
        }
        return result.isEmpty() ? null : result;
    }

    private static String strings(JsonNode values, String field, int max, List<Warning> warnings) {
        if (values == null || values.isNull()) { return null; }
        if (!values.isArray()) {
            warnings.add(new Warning("FIELD_INVALID", field, "上游列表类型不合法")); return null;
        }
        var items = new LinkedHashSet<String>();
        for (JsonNode item : values) {
            if (!item.isString()) { warnings.add(new Warning("FIELD_INVALID", field, "上游列表项类型不合法")); return null; }
            if (!item.asString().trim().isEmpty()) { items.add(item.asString().trim()); }
        }
        String joined = String.join("、", items);
        if (joined.length() > max) {
            warnings.add(new Warning("FIELD_TOO_LONG", field, "上游列表过长，未截断或应用")); return null;
        }
        return joined.isEmpty() ? null : joined;
    }

    private static Integer year(JsonNode value, List<Warning> warnings) {
        String date = text(value, "publishYear", YEAR_SOURCE_LIMIT, warnings);
        if (date == null) { return null; }
        Matcher matcher = YEAR.matcher(date);
        if (!matcher.find()) {
            warnings.add(new Warning("YEAR_AMBIGUOUS", "publishYear", "出版日期没有唯一四位年份")); return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        if (matcher.find()) {
            warnings.add(new Warning("YEAR_AMBIGUOUS", "publishYear", "出版日期含多个年份")); return null;
        }
        return year;
    }

    /** First positive integral cover ID; non-positive IDs (-1 = removed) are skipped; any malformed entry is rejected. */
    private static Long coverId(JsonNode covers, List<Warning> warnings) {
        if (!covers.isArray()) { return rejectCover(warnings); }
        Long first = null;
        for (JsonNode id : covers) {
            if (!id.isIntegralNumber() || !id.canConvertToLong()) { return rejectCover(warnings); }
            long value = id.asLong();
            if (value > 0 && first == null) { first = value; }
        }
        return first;
    }

    private static Long rejectCover(List<Warning> warnings) {
        warnings.add(new Warning("COVER_REFERENCE_REJECTED", "cover", "上游封面引用不在许可范围，未发起请求"));
        return null;
    }

    private static List<String> authorKeys(JsonNode authors, List<Warning> warnings) {
        if (authors == null || authors.isNull()) { return List.of(); }
        if (!authors.isArray()) {
            warnings.add(new Warning("FIELD_INVALID", "author", "上游作者引用类型不合法")); return List.of();
        }
        var keys = new LinkedHashSet<String>();
        for (JsonNode author : authors) {
            JsonNode key = author.isObject() ? author.get("key") : null;
            Matcher matcher = key != null && key.isString() ? AUTHOR_KEY.matcher(key.asString()) : null;
            if (matcher == null || !matcher.matches()) {
                warnings.add(new Warning("FIELD_INVALID", "author", "上游作者引用不合法")); return List.of();
            }
            keys.add(matcher.group(1));
        }
        return new ArrayList<>(keys);
    }

    private static String workKey(JsonNode works) {
        if (works == null || !works.isArray()) { return null; }
        var keys = new LinkedHashSet<String>();
        for (JsonNode work : works) {
            JsonNode key = work.isObject() ? work.get("key") : null;
            Matcher matcher = key != null && key.isString() ? WORK_KEY.matcher(key.asString()) : null;
            if (matcher == null || !matcher.matches()) { return null; }
            keys.add(matcher.group(1));
        }
        return keys.size() == 1 ? keys.iterator().next() : null;
    }
}
