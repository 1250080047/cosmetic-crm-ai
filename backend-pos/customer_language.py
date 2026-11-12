"""Customer-segment names and cashier wording; no database or model changes."""
import re
import unicodedata

# Segment names given by the clustering rules (RFM/LRFM marketing vocabulary).
VIP = "Khách hàng VIP"                         # top spenders, buy often, still active (Champions)
LOYAL = "Khách hàng trung thành"               # bought recently and often (Loyal)
POTENTIAL = "Khách hàng tiềm năng"             # bought recently, high spend, not yet often (Potential loyalist)
PROMISING = "Khách hàng triển vọng"            # bought recently, low spend, not yet often (Promising)
NEW = "Khách hàng mới"                         # short relationship (L), still active (New customers)
NEED_ATTENTION = "Khách hàng cần quan tâm"     # buy often, last visit neither recent nor long ago (Need attention)
ABOUT_TO_SLEEP = "Khách hàng sắp ngủ đông"     # few orders, last visit neither recent nor long ago (About to sleep)
AT_RISK = "Khách hàng có nguy cơ rời bỏ"       # used to buy often, long time no visit (At risk)
HIBERNATING = "Khách hàng ngủ đông"            # few orders, long time no visit (Hibernating)
NO_PURCHASE = "Chưa mua hàng"                  # registered, no invoice yet: outside the groups
SEGMENT_NAMES = (VIP, LOYAL, POTENTIAL, PROMISING, NEW, NEED_ATTENTION, ABOUT_TO_SLEEP, AT_RISK, HIBERNATING, NO_PURCHASE)

# Short names used by model v1 before 12/11/2026 (kept so old records still read correctly).
PREVIOUS_NAMES = {
    "vip": VIP, "mua deu": LOYAL, "tiem nang": POTENTIAL, "mua gan day": PROMISING, "moi quen": NEW,
    "khach quen": NEED_ATTENTION, "mua thinh thoang": ABOUT_TO_SLEEP, "can giu chan": AT_RISK,
    "lau chua ghe": HIBERNATING, "khach moi": NO_PURCHASE,
}


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFD", value or "")
    value = "".join(char for char in value if unicodedata.category(char) != "Mn")
    return re.sub(r"\s+", " ", value.replace("đ", "d").replace("Đ", "D").lower()).strip()


def segment_label(name: str) -> str:
    # A spend qualifier ("· chi tiêu cao") separates two groups with the same base name: keep it.
    if " · " in (name or ""):
        base, qualifier = name.split(" · ", 1)
        return segment_label(base) + " · " + qualifier
    if name in SEGMENT_NAMES:
        return name
    text = normalize_text(name)
    if text in PREVIOUS_NAMES:
        return PREVIOUS_NAMES[text]
    # Older free-text labels from earlier versions of the project.
    if any(word in text for word in ("ngu dong", "roi bo", "at-risk", "lost", "lau chua")):
        return HIBERNATING
    if "vip" in text or "champion" in text:
        return VIP
    if "moi" in text or "vang lai" in text:
        return NEW
    if "tiem nang" in text:
        return POTENTIAL
    if "than thiet" in text or "thanh vien" in text:
        return LOYAL
    if "phan khuc" in text:
        number = re.search(r"\d+", text)
        return "Nhóm " + number.group() if number else "Khách hàng"
    return name or "Khách hàng"


def cashier_script(discount: float = 0, gift: str = "", original: str = "") -> str:
    """Preserve professional custom wording; replace internal labels and blanket promises."""
    text = normalize_text(original)
    forbidden = ("ngu dong", "roi bo", "phan khuc", "nhom ", "at-risk", "champions",
                 "kich hoat lai", "upsell", "nang hang vip", "dac quyen cua")
    if original.strip() and not any(term in text for term in forbidden) and "chao chi" not in text:
        return original.strip()
    parts = ["Xin chào anh/chị. Cảm ơn anh/chị đã ghé cửa hàng."]
    if discount > 0:
        amount = f"{float(discount):g}".replace(".", ",")
        parts.append(f"Đơn hàng hôm nay của anh/chị được giảm {amount}%.")
    if gift.strip():
        clean_gift = re.sub(r"(?i)^(?:tặng\s*(?:kèm)?\s*(?:1\s*)?|quà tặng\s*:\s*)", "", gift).strip()
        clean_gift = re.sub(r"(?i)\s*(?:kích hoạt lại|cho nhóm|dành cho nhóm|khách ngủ đông|khách rời bỏ).*", "", clean_gift).strip()
        parts.append(f"Ưu đãi kèm theo là {clean_gift}. Em xin kiểm tra và chuẩn bị quà theo chương trình cho anh/chị.")
    parts.append("Anh/chị có cần em tư vấn thêm sản phẩm phù hợp không ạ?")
    return " ".join(parts)
