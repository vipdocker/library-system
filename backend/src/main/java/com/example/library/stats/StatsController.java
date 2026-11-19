package com.example.library.stats;

import java.util.List;

import com.example.library.common.web.PageResponse;
import com.example.library.stats.dto.LoanSeriesPoint;
import com.example.library.stats.dto.OverdueItem;
import com.example.library.stats.dto.StatsOverview;
import com.example.library.stats.dto.TopBook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计与报表接口（design-v0.1 第 8.4 节 stats 部分，仅 ADMIN）。
 * 授权由 SecurityConfig URL 规则保证：/api/stats/**、/api/reports/** 落在“其余 /api/** 仅 ADMIN”
 * （AC-AUTH-04：匿名 401 AUTH_REQUIRED / READER 403 FORBIDDEN）。全部 GET，只读。
 */
@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 200 StatsOverview（AC-STAT-01）。 */
    @GetMapping("/api/stats/overview")
    public StatsOverview overview() {
        return statsService.overview();
    }

    /**
     * 200 LoanSeriesPoint[]（AC-STAT-02）。from/to 含两端，默认最近 30 天；
     * granularity=DAY|MONTH 默认 DAY；400：格式非法、from>to、跨度 >366 天、granularity 非法。
     */
    @GetMapping("/api/stats/loans")
    public List<LoanSeriesPoint> loanSeries(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String granularity) {
        return statsService.loanSeries(from, to, granularity);
    }

    /** 200 TopBook[]（AC-STAT-03）：borrowCount 降序、title 升序；limit 1..50 默认 10；400 同上。 */
    @GetMapping("/api/stats/top-books")
    public List<TopBook> topBooks(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) Integer limit) {
        return statsService.topBooks(from, to, limit);
    }

    /**
     * 200 Page<OverdueItem>（AC-STAT-04）。page/size 见第 8.1 节；
     * sort 白名单 overdueDays、dueDate，默认 overdueDays,desc；越界或非法 400。
     */
    @GetMapping("/api/reports/overdue")
    public PageResponse<OverdueItem> overdueReport(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String sort) {
        return statsService.overdueReport(page, size, sort);
    }
}