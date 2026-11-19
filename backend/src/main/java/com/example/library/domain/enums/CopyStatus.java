package com.example.library.domain.enums;

/** 副本状态（book_copy.status）。BORROWED 只能由借还流程的条件 UPDATE 设置。 */
public enum CopyStatus {
    AVAILABLE,
    BORROWED,
    UNAVAILABLE
}