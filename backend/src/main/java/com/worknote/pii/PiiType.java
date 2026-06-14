package com.worknote.pii;

import java.util.Collection;
import java.util.stream.Collectors;

/** 탐지 PII 유형. types 컬럼 직렬화는 enum name 소문자 CSV. */
public enum PiiType {
    RRN, PHONE, EMAIL, CARD, BIZ, PASSPORT, DRIVER;

    public static String csv(Collection<PiiType> types) {
        return types.stream().map(t -> t.name().toLowerCase()).sorted().collect(Collectors.joining(","));
    }
}
