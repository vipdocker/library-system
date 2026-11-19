// 热门图书榜：排名徽章 + 按借阅次数比例的横向条形（纯 CSS，无图表库）。
// 排序由服务端保证（borrowCount 降序、同次数按 title 升序），前端按返回顺序展示。
import type { TopBook } from '../../../api/types';

export interface TopBooksListProps {
  books: TopBook[];
}

export function TopBooksList({ books }: TopBooksListProps) {
  const max = Math.max(1, ...books.map((b) => b.borrowCount));
  return (
    <ol className="top-books" aria-label="热门图书榜">
      {books.map((book, index) => (
        <li key={book.bookId} className="top-book">
          <span
            className={`top-book-rank${index < 3 ? ` top-book-rank--${index + 1}` : ''}`}
            aria-label={`第 ${index + 1} 名`}
          >
            {index + 1}
          </span>
          <div className="top-book-main">
            <div className="top-book-head">
              <span className="top-book-title">{book.title}</span>
              <span className="top-book-count">{book.borrowCount} 次</span>
            </div>
            <div className="top-book-meta">
              {book.author} · <span className="top-book-isbn">{book.isbn}</span>
            </div>
            <div className="top-book-track" aria-hidden="true">
              <div
                className="top-book-bar"
                style={{
                  width: `${(book.borrowCount / max) * 100}%`,
                  animationDelay: `${index * 40}ms`,
                }}
              />
            </div>
          </div>
        </li>
      ))}
    </ol>
  );
}