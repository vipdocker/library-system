package com.example.library.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.util.Texts;
import com.example.library.common.web.PageRequestParams;
import com.example.library.common.web.PageResponse;
import com.example.library.stats.dto.LoanSeriesPoint;
import com.example.library.stats.dto.OverdueItem;
import com.example.library.stats.dto.StatsOverview;
import com.example.library.stats.dto.TopBook;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统计服务（design-v0.1 第 8.4 节 stats；AC-STAT-01..04）。
 * - 只读；授权由 SecurityConfig URL 规则保证（/api/stats/**、/api/reports/** 仅 ADMIN）；
 * - today 一律取注入的 Clock（K7，lib.timezone 默认 Asia/Shanghai），与 T05/T06 逾期口径一致；
 * - 参数校验：日期严格 YYYY-MM-DD、from<=to、跨度 <=366 天（to-from 的自然日差）、
 *   granularity ∈ {DAY, MONTH}、limit 1..50、分页排序白名单（overdueDays、dueDate）；
 * - 序列补齐：区间内每个周期都有一项，无数据补 0；月粒度边缘月只统计落在区间内的日期。
 */
@Service
public class StatsService {

    /** 跨度上限：to - from 的自然日差 ≤366（含两端最多 367 个日期，与前端 range.ts 一致）。 */
    static final int MAX_SPAN_DAYS = 366;
    /** 默认区间：最近 30 天（含今天），即 [today-29, today]。 */
    static final int DEFAULT_RANGE_DAYS = 30;
    static final int DEFAULT_TOP_LIMIT = 10;
    static final int MAX_TOP_LIMIT = 50;

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final Set<String> OVERDUE_SORT_WHITELIST = Set.of("overdueDays", "dueDate");
    private static final Sort DEFAULT_OVERDUE_SORT = Sort.by(Sort.Direction.DESC, "overdueDays");

    private final StatsQueryRepository queryRepository;
    private final Clock clock;

    public StatsService(StatsQueryRepository queryRepository, Clock clock) {
        this.queryRepository = queryRepository;
        this.clock = clock;
    }

    /** GET /api/stats/overview（AC-STAT-01）。 */
    @Transactional(readOnly = true)
    public StatsOverview overview() {
        return queryRepository.overview(today());
    }

    /** GET /api/stats/loans（AC-STAT-02）：区间内每个周期一项，无数据补 0。 */
    @Transactional(readOnly = true)
    public List<LoanSeriesPoint> loanSeries(String from, String to, String granularity) {
        StatsGranularity g = parseGranularity(granularity);
        LocalDate[] range = parseRange(from, to);
        LocalDate f = range[0];
        LocalDate t = range[1];
        Map<String, Long> borrows = queryRepository.borrowCounts(f, t, g);
        Map<String, Long> returns = queryRepository.returnCounts(f, t, g);
        List<LoanSeriesPoint> points = new ArrayList<>();
        for (String period : periods(f, t, g)) {
            points.add(new LoanSeriesPoint(period,
                    borrows.getOrDefault(period, 0L), returns.getOrDefault(period, 0L)));
        }
        return points;
    }

    /** GET /api/stats/top-books（AC-STAT-03）：borrowCount 降序，相同时 title 升序；limit 1..50 默认 10。 */
    @Transactional(readOnly = true)
    public List<TopBook> topBooks(String from, String to, Integer limit) {
        LocalDate[] range = parseRange(from, to);
        int l = limit == null ? DEFAULT_TOP_LIMIT : limit;
        if (l < 1 || l > MAX_TOP_LIMIT) {
            throw fieldError("limit", "limit 必须在 1–50 之间");
        }
        return queryRepository.topBooks(range[0], range[1], l);
    }

    /** GET /api/reports/overdue（AC-STAT-04）：未还且 due_date < today；默认 overdueDays,desc。 */
    @Transactional(readOnly = true)
    public PageResponse<OverdueItem> overdueReport(Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestParams.toPageable(page, size, sort,
                OVERDUE_SORT_WHITELIST, DEFAULT_OVERDUE_SORT);
        LocalDate today = today();
        long total = queryRepository.countOverdue(today);
        List<StatsQueryRepository.OverdueRow> rows = total == 0 ? List.of()
                : queryRepository.overduePage(today,
                        pageable.getSort().iterator().next().getProperty(),
                        pageable.getSort().iterator().next().isAscending(),
                        (int) pageable.getOffset(), pageable.getPageSize());
        List<OverdueItem> items = rows.stream().map(row -> toItem(row, today)).toList();
        int totalPages = (int) ((total + pageable.getPageSize() - 1) / pageable.getPageSize());
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), total, totalPages);
    }

    // ---- 内部辅助 ----

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static OverdueItem toItem(StatsQueryRepository.OverdueRow row, LocalDate today) {
        long overdueDays = ChronoUnit.DAYS.between(LocalDate.parse(row.dueDate()), today);
        return new OverdueItem(row.loanId(),
                new OverdueItem.ReaderRef(row.readerId(), row.cardNo(), row.readerName(), row.phone()),
                new OverdueItem.BookRef(row.bookId(), row.title(), row.isbn()),
                row.barcode(), row.borrowDate(), row.dueDate(), overdueDays);
    }

    /** 区间内全部周期标签：DAY 为每个日期，MONTH 为区间覆盖到的每个自然月。 */
    private static List<String> periods(LocalDate from, LocalDate to, StatsGranularity g) {
        List<String> result = new ArrayList<>();
        if (g == StatsGranularity.DAY) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                result.add(d.toString());
            }
        } else {
            YearMonth end = YearMonth.from(to);
            for (YearMonth m = YearMonth.from(from); !m.isAfter(end); m = m.plusMonths(1)) {
                result.add(m.toString());
            }
        }
        return result;
    }

    /** from/to 各自缺省：from=today-29、to=today（默认最近 30 天，含今天）。 */
    private LocalDate[] parseRange(String from, String to) {
        LocalDate today = today();
        LocalDate f = parseDate(from, "from");
        LocalDate t = parseDate(to, "to");
        if (f == null) {
            f = today.minusDays(DEFAULT_RANGE_DAYS - 1L);
        }
        if (t == null) {
            t = today;
        }
        if (f.isAfter(t)) {
            throw fieldError("from", "from 不能晚于 to");
        }
        if (ChronoUnit.DAYS.between(f, t) > MAX_SPAN_DAYS) {
            throw fieldError("to", "统计区间跨度不能超过 " + MAX_SPAN_DAYS + " 天");
        }
        return new LocalDate[]{f, t};
    }

    /** 严格 YYYY-MM-DD（拒绝 2026-1-01、2026-02-30 等）；null/空白按缺省处理（第 8.1 节）。 */
    private static LocalDate parseDate(String raw, String field) {
        String value = Texts.trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (!DATE_PATTERN.matcher(value).matches()) {
            throw fieldError(field, "日期格式必须为 YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(value, STRICT_DATE);
        } catch (DateTimeParseException ex) {
            throw fieldError(field, "日期不存在或非法：" + value);
        }
    }

    private static StatsGranularity parseGranularity(String raw) {
        String value = Texts.trimToNull(raw);
        if (value == null) {
            return StatsGranularity.DAY;
        }
        if ("DAY".equals(value)) {
            return StatsGranularity.DAY;
        }
        if ("MONTH".equals(value)) {
            return StatsGranularity.MONTH;
        }
        throw fieldError("granularity", "granularity 只能为 DAY 或 MONTH");
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                Map.of(), List.of(new ErrorResponse.FieldErrorItem(field, message)));
    }
}