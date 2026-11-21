package com.example.library.dunning;

import java.util.Map;
import java.util.Set;
import com.example.library.common.util.Texts;
import com.example.library.domain.enums.DunningMethod;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DunningRequest(@NotNull DunningMethod method, @Size(max = 500) String note) {
    public DunningRequest {
        note = Texts.trimToNull(note);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DunningRequest fromJson(Map<String, Object> fields) {
        if (fields == null || !Set.of("method", "note").containsAll(fields.keySet())
                || !(fields.get("method") instanceof String method)
                || (fields.get("note") != null && !(fields.get("note") instanceof String))) {
            throw new IllegalArgumentException("method 必须为合法方式，note 必须为字符串或 null，不能包含其他字段");
        }
        return new DunningRequest(DunningMethod.valueOf(method.trim()), (String) fields.get("note"));
    }
}
