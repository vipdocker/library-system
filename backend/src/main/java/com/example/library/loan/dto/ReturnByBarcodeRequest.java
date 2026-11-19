package com.example.library.loan.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按条码还书请求（design-v0.1 第 8.4 节）：{copyBarcode}。
 */
public record ReturnByBarcodeRequest(@NotBlank @Size(max = 64) String copyBarcode) {

    public ReturnByBarcodeRequest {
        copyBarcode = Texts.trimToNull(copyBarcode);
    }
}