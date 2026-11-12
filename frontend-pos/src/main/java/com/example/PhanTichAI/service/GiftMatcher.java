package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.Product;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GiftMatcher {
    private GiftMatcher() {}
    private static final List<String> FAMILIES = List.of(
            "sua rua mat", "kem chong nang", "mat na", "sua tam", "dau goi", "dau xa",
            "kem duong", "nuoc hoa hong", "tay trang", "son", "serum", "tinh chat",
            "xa bong", "xa phong", "co trang diem", "bong mut", "duong the", "dau duong");
    private static final Set<String> FILLER = Set.of(
            "tang", "kem", "qua", "mien", "phi", "mot", "san", "pham", "chai", "hop", "goi",
            "cai", "phan", "suat", "va", "cho", "khach", "hang", "chuong", "trinh", "tri", "an");

    public static boolean matches(String description, Product product) {
        if (description == null || description.isBlank() || product == null ||
                !Boolean.TRUE.equals(product.getIsActive())) return false;
        String gift = CustomerLanguage.normalize(description);
        String name = CustomerLanguage.normalize(product.getProductName());
        if (gift.matches(".*(voucher|phieu mua|phieu giam|tich diem|hoan tien|ma giam).*")) return false;
        if ((gift.contains("sample") || gift.contains("mini size") || gift.contains("minisize"))
                && !name.matches(".*(sample|mini size|minisize).*")) return false;
        if (gift.contains(name) && name.length() > 10) return true;
        String family = FAMILIES.stream().filter(gift::contains).findFirst().orElse(null);
        if (family == null || !name.contains(family)) return false;
        String remainder = gift.replace(family, "");
        return Arrays.stream(remainder.split("\\s+"))
                .filter(token -> !token.isBlank() && !FILLER.contains(token) && !token.matches("\\d{1,2}"))
                .allMatch(name::contains);
    }

    public static int quantityLimit(String description) {
        String normalized = CustomerLanguage.normalize(description);
        Matcher match = Pattern.compile("(?:tang|qua tang)\\s+(\\d{1,2})\\b").matcher(normalized);
        if (match.find()) return Math.max(1, Math.min(10, Integer.parseInt(match.group(1))));
        return 1;
    }
}
