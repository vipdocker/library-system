// AC-SRCH-02：URL query ⇄ BookQuery 换算；非法值回退默认；默认值省略保持 URL 简洁。
import { describe, expect, it } from 'vitest';
import { DEFAULT_BOOK_QUERY } from '../api';
import { bookQueryToParams, parseBookQuery } from '../searchQuery';

describe('AC-SRCH-02 parseBookQuery', () => {
  it('空 URL 得到默认查询', () => {
    expect(parseBookQuery(new URLSearchParams())).toEqual(DEFAULT_BOOK_QUERY);
  });

  it('完整条件逐项解析', () => {
    const params = new URLSearchParams(
      'q=三体&field=isbn&categoryId=2&availableOnly=true&page=3&size=50&sort=publishYear,desc',
    );
    expect(parseBookQuery(params)).toEqual({
      q: '三体',
      field: 'isbn',
      categoryId: 2,
      availableOnly: true,
      page: 3,
      size: 50,
      sort: 'publishYear,desc',
    });
  });

  it('非法值回退默认：字段、排序白名单、页码、size、分类 id', () => {
    const params = new URLSearchParams(
      'field=hack&sort=drop,desc&page=-1&size=999&categoryId=abc&availableOnly=yes',
    );
    expect(parseBookQuery(params)).toEqual(DEFAULT_BOOK_QUERY);
  });

  it('q 超过 100 字符截断到 100（契约 q ≤100）', () => {
    const params = new URLSearchParams(`q=${'a'.repeat(150)}`);
    expect(parseBookQuery(params).q).toHaveLength(100);
  });
});

describe('AC-SRCH-02 bookQueryToParams', () => {
  it('默认查询生成空参数；往返一致', () => {
    expect(bookQueryToParams(DEFAULT_BOOK_QUERY).toString()).toBe('');
    const query = {
      q: '算法',
      field: 'title' as const,
      categoryId: 3,
      availableOnly: true,
      page: 2,
      size: 10,
      sort: 'createdAt,desc',
    };
    const roundTrip = parseBookQuery(bookQueryToParams(query));
    expect(roundTrip).toEqual(query);
  });
});