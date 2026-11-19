package com.example.library.loan.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 借书请求（design-v0.1 第 8.4 节）：{readerCardNo, copyBarcode}。
 * 紧凑构造器先 trim 再校验（第 8.1 节）。
 */
public record BorrowRequest(
        @NotBlank @Size(max = 64) String readerCardNo,
        @NotBlank @Size(max = 64) String copyBarcode) {

    public BorrowRequest {
        readerCardNo = Texts.trimToNull(readerCardNo);
        copyBarcode = Texts.trimToNull(copyBarcode);
    }
}