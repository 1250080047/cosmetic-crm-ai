package com.example.PhanTichAI.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.example.PhanTichAI.entity.Strategy;

public final class CustomerLanguage {
    private CustomerLanguage() {}

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Segment names given by the clustering service (same list as backend-pos/customer_language.py). */
    public static final Set<String> SEGMENT_NAMES = Set.of(
            "Khách hàng VIP", "Khách hàng trung thành", "Khách hàng tiềm năng", "Khách hàng triển vọng",
            "Khách hàng mới", "Khách hàng cần quan tâm", "Khách hàng sắp ngủ đông",
            "Khách hàng có nguy cơ rời bỏ", "Khách hàng ngủ đông", "Chưa mua hàng");

    /** Short names used by model v1 before 12/11/2026, so old records still read correctly. */
    private static final Map<String, String> PREVIOUS_NAMES = Map.of(
            "vip", "Khách hàng VIP", "mua deu", "Khách hàng trung thành", "tiem nang", "Khách hàng tiềm năng",
            "mua gan day", "Khách hàng triển vọng", "moi quen", "Khách hàng mới", "khach quen", "Khách hàng cần quan tâm",
            "mua thinh thoang", "Khách hàng sắp ngủ đông", "can giu chan", "Khách hàng có nguy cơ rời bỏ",
            "lau chua ghe", "Khách hàng ngủ đông", "khach moi", "Chưa mua hàng");

    public static String segment(String name) {
        if (name != null && name.contains(" · ")) {
            int suffix = name.indexOf(" · ");
            return segment(name.substring(0,suffix)) + name.substring(suffix);
        }
        if (SEGMENT_NAMES.contains(name)) return name;
        String text = normalize(name);
        if (PREVIOUS_NAMES.containsKey(text)) return PREVIOUS_NAMES.get(text);
        // Older free-text labels from earlier versions of the project.
        if (text.matches(".*(ngu dong|roi bo|at risk|lost|lau chua).*")) return "Khách hàng ngủ đông";
        if (text.contains("vip") || text.contains("champion")) return "Khách hàng VIP";
        if (text.contains("moi") || text.contains("vang lai")) return "Khách hàng mới";
        if (text.contains("tiem nang")) return "Khách hàng tiềm năng";
        if (text.contains("than thiet") || text.contains("thanh vien")) return "Khách hàng trung thành";
        if (text.contains("phan khuc")) {
            Matcher number = Pattern.compile("\\d+").matcher(text);
            return number.find() ? "Nhóm " + number.group() : "Khách hàng";
        }
        return name == null || name.isBlank() ? "Khách hàng" : name;
    }

    public static String cashierScript(String original, BigDecimal discount, String gift) {
        String normalized = normalize(original);
        boolean internal = Pattern.compile("ngu dong|roi bo|phan khuc|nhom |at risk|champion|kich hoat lai|upsell|vip|chao chi")
                .matcher(normalized).find();
        if (original != null && !original.isBlank() && !internal) return original.trim();
        StringBuilder result = new StringBuilder("Xin chào anh/chị. Cảm ơn anh/chị đã ghé cửa hàng.");
        if (discount != null && discount.signum() > 0)
            result.append(" Đơn hàng hôm nay của anh/chị được giảm ")
                    .append(discount.stripTrailingZeros().toPlainString().replace('.', ',')).append("%.");
        if (gift != null && !gift.isBlank())
            result.append(" Ưu đãi kèm theo là ").append(gift.trim().replaceAll("(?iu)\\s*(?:kích hoạt lại|cho nhóm|dành cho nhóm|khách ngủ đông|khách rời bỏ).*", ""))
                    .append(". Em xin kiểm tra và chuẩn bị quà theo chương trình cho anh/chị.");
        result.append(" Anh/chị có cần em tư vấn thêm sản phẩm phù hợp không ạ?");
        return result.toString();
    }

    public static String strategyTitle(String value) {
        if (value == null) return null;
        return value.replaceAll("(?iu)Khách\\s+Ngủ\\s+Đông\\s*/\\s*Rời\\s+Bỏ", "Khách hàng ngủ đông")
                .replaceAll("(?iu)VIP\\s*/\\s*Champions", "Khách hàng VIP")
                .replaceAll("(?iu)VIP Kim Cương\\s*\\(Super VIP\\)", "Khách hàng VIP")
                .replaceAll("(?iu)Khách Hàng Tiềm Năng", "Khách hàng tiềm năng")
                .replaceAll("(?iu)Phân Khúc Khách Hàng Cấp\\s+(\\d+)", "Nhóm $1");
    }

    // A detached presentation copy: reading an offer must never rewrite the stored entity.
    public static Strategy presentStrategy(Strategy source) {
        Strategy view = new Strategy();
        view.setId(source.getId());
        view.setSegmentId(source.getSegmentId());
        view.setTitle(strategyTitle(source.getTitle()));
        view.setDiscountPercent(source.getDiscountPercent());
        view.setGiftDescription(source.getGiftDescription());
        view.setCashierScript(cashierScript(source.getCashierScript(), source.getDiscountPercent(), source.getGiftDescription()));
        view.setIsActive(source.getIsActive());
        view.setUpdatedAt(source.getUpdatedAt());
        return view;
    }
}
