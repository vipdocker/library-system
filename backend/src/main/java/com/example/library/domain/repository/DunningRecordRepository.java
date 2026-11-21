package com.example.library.domain.repository;

import java.time.Instant;
import java.util.List;
import com.example.library.domain.entity.DunningRecord;
import com.example.library.domain.entity.Loan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DunningRecordRepository extends JpaRepository<DunningRecord, Long> {
    List<DunningRecord> findByLoanIdOrderByCreatedAtAscIdAsc(Long loanId);

    interface CaseRow {
        Loan getLoan();
        long getRecordCount();
        Instant getLastRecordedAt();
    }

    @Query(value = """
        select l as loan, count(d) as recordCount, max(d.createdAt) as lastRecordedAt
        from DunningRecord d join d.loan l
        where (:status = 'ALL' or (:status = 'OPEN' and l.returnedAt is null)
            or (:status = 'CLOSED' and l.returnedAt is not null))
        group by l
        order by max(d.createdAt) desc, l.id desc
        """, countQuery = """
        select count(l) from Loan l
        where exists (select d.id from DunningRecord d where d.loan = l)
            and (:status = 'ALL' or (:status = 'OPEN' and l.returnedAt is null)
                or (:status = 'CLOSED' and l.returnedAt is not null))
        """)
    Page<CaseRow> findCases(@Param("status") String status, Pageable pageable);
}
