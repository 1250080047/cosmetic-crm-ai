"""Vietnamese review sentiment for cosmetics (Essentials of Marketing Analytics, chapter 10: text mining & sentiment).

Runs entirely on this computer — the customer's words are never sent to an outside service.

How a review is read:
1. the text is lower-cased and accents are removed, so "khong thom" and "không thơm" read the same;
2. phrases from a small cosmetics lexicon are found, longest first ("không hiệu quả" before "hiệu quả");
3. a negation just before a word flips it: "không thơm" → negative, "không bết" / "không bị kích ứng" → positive;
4. intensifiers ("rất", "quá", "cực") weigh more;
5. the text score is combined with the stars (1–5) into POSITIVE / NEUTRAL / NEGATIVE;
6. topics (mùi, giá, bao bì, hiệu quả, kết cấu, kích ứng, phục vụ) and skin-irritation reports are flagged —
   an irritation report is sent to the manager at once.
"""
from __future__ import annotations

import math
import re
import unicodedata

# Without accents some words collide ("rất"/"rát" → rat, "kém"/"kem", "hồng"/"hỏng"/"hông" → hong, "đắt"/"đạt" → dat,
# "yêu"/"yếu" → yeu, "dở"/"đỏ"/"do" → do). Those are only used inside unambiguous phrases.
POSITIVE = {
    "thich": 1, "rat thich": 1.5, "tot": 1, "dep": 1, "thom": 1, "thom lau": 1.5, "min": 1, "min da": 1, "mem": 1,
    "mem muot": 1.5, "muot": 1, "du am": 1, "cap am": 1, "hieu qua": 1.2, "ung y": 1, "on": 0.6, "on ap": 0.8, "xuat sac": 2,
    "tuyet": 1.5, "tuyet voi": 2, "hai long": 1.5, "re": 0.8, "dang tien": 1.5, "hop ly": 0.8, "diu nhe": 1, "diu": 0.8,
    "sach": 0.8, "sach sau": 1, "sang da": 1.2, "trang da": 1, "mo tham": 1.2, "giam mun": 1.2, "het mun": 1.5,
    "tham nhanh": 1, "nhanh tham": 1, "chuan": 0.8, "chinh hang": 1, "hang xin": 1, "ok": 0.6, "good": 1, "love": 1,
    "great": 1.5, "nen mua": 1.5, "se mua lai": 1.5, "mua lai": 1, "phu hop": 0.8, "nhe nhang": 0.8, "tuoi mat": 1,
    "giu mau": 1, "ben mau": 1, "lau troi": 1, "an toan": 1, "lanh tinh": 1, "mong nhe": 0.8,
}
NEGATIVE = {
    "te": 1.5, "do te": 1.5, "kem chat luong": 1.5, "chat luong kem": 1.5, "that vong": 2, "kho chiu": 1.5, "bet": 1.2,
    "bet dinh": 1.5, "nhon": 1, "ngua": 1.5, "noi man": 2, "man do": 2, "di ung": 2, "kich ung": 2, "phan ung": 1.5,
    "noi mun": 1.5, "len mun": 1.5, "bi da": 1.2, "rat da": 1.5, "bi rat": 1.5, "do rat": 2, "nong rat": 2, "hac": 1.2,
    "mui nong": 1, "mui kho chiu": 2, "gia dat": 0.8, "hoi dat": 0.8, "dat qua": 1, "dat do": 1, "hoi mac": 0.8,
    "mac qua": 1, "bi hong": 1.5, "bi vo": 1.2, "vo nat": 1.2, "mop": 1, "ro ri": 1.5, "chay nuoc": 1, "hang gia": 2.5,
    "fake": 2.5, "khong hieu qua": 1.5, "vo dung": 2, "phi tien": 2, "bad": 1.5, "kho da": 1, "lam kho": 1,
    "troi nhanh": 1, "xuong mau": 1, "sung": 1.5, "khong nhu quang cao": 1.5, "het han": 1.5, "loang": 0.8,
}
NEGATIONS = {"khong", "chang", "cha", "chua", "ko", "k", "hok", "khg", "kg", "deo"}
INTENSIFIERS = {"rat": 1.5, "qua": 1.4, "cuc": 1.6, "sieu": 1.6, "lam": 1.3, "vo cung": 1.7, "hoi": 0.7}
TOPICS = {
    "Mùi hương": ["mui", "thom", "huong", "hac", "mui nong", "thom lau"],
    "Giá": ["gia", "gia dat", "hoi dat", "dat qua", "re", "hoi mac", "mac qua", "dang tien", "phi tien", "gia ca"],
    "Bao bì": ["bao bi", "chai", "hop", "vo chai", "voi", "nap", "dong goi", "mop", "bi vo", "ro ri", "het han"],
    "Hiệu quả": ["hieu qua", "mo tham", "trang da", "sang da", "giam mun", "het mun", "du am", "cap am", "khong hieu qua",
                 "giu mau", "ben mau", "lau troi", "troi nhanh", "khong nhu quang cao"],
    "Kết cấu": ["tham nhanh", "nhanh tham", "bet", "bet dinh", "nhon", "hoi dac", "dac qua", "hoi long", "long qua",
                "mong nhe", "min", "mem", "kho da", "lam kho"],
    "Kích ứng da": ["di ung", "kich ung", "phan ung", "noi man", "man do", "ngua", "rat da", "bi rat", "do rat", "nong rat",
                    "noi mun", "len mun", "sung", "bi da"],
    "Phục vụ": ["nhan vien", "phuc vu", "tu van", "thu ngan", "cua hang"],
}
IRRITATION = {"di ung", "kich ung", "phan ung", "noi man", "man do", "ngua", "rat da", "bi rat", "do rat", "nong rat",
              "noi mun", "len mun", "sung"}
_LEXICON = {**{k: v for k, v in POSITIVE.items()}, **{k: -v for k, v in NEGATIVE.items()}}
_MAX_WORDS = max(len(k.split()) for k in list(_LEXICON) + list(INTENSIFIERS))


def strip_accents(text: str) -> str:
    text = unicodedata.normalize("NFD", (text or "").lower()).replace("đ", "d")
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def _phrases(tokens: list[str]) -> list[tuple[int, int, str]]:
    """Longest lexicon phrases in the text: (start, end, phrase)."""
    found, i = [], 0
    while i < len(tokens):
        for size in range(min(_MAX_WORDS, len(tokens) - i), 0, -1):
            phrase = " ".join(tokens[i:i + size])
            if phrase in _LEXICON:
                found.append((i, i + size, phrase))
                i += size
                break
        else:
            i += 1
    return found


def analyze(text: str, rating: int | None = None) -> dict:
    tokens = strip_accents(text).split()
    words = " " + " ".join(tokens) + " "
    score, hits, irritation = 0.0, [], False
    for start, end, phrase in _phrases(tokens):
        polarity = _LEXICON[phrase]
        before = tokens[max(0, start - 3):start]
        negated = any(word in NEGATIONS for word in before) and not phrase.startswith("khong")
        weight = 1.0
        for size in (2, 1):
            if start - size >= 0 and " ".join(tokens[start - size:start]) in INTENSIFIERS:
                weight = INTENSIFIERS[" ".join(tokens[start - size:start])]
                break
        value = -polarity * 0.8 if negated else polarity * weight
        score += value
        hits.append({"phrase": phrase, "value": round(value, 2), "negated": negated})
        if phrase in IRRITATION and not negated:
            irritation = True
    text_signal = math.tanh(score / 2) if hits else None
    star_signal = None if rating is None else (max(1, min(5, int(rating))) - 3) / 2
    if text_signal is None and star_signal is None:
        final = 0.0
    elif text_signal is None:
        final = star_signal
    elif star_signal is None:
        final = text_signal
    else:
        final = 0.5 * text_signal + 0.5 * star_signal
    if irritation:
        final = min(final, -0.3)   # a skin reaction is a complaint whatever the stars say
    sentiment = "POSITIVE" if final >= 0.25 else "NEGATIVE" if final <= -0.25 else "NEUTRAL"
    topics = [name for name, keys in TOPICS.items() if any(" " + key + " " in words for key in keys)]
    return {"sentiment": sentiment, "score": round(final, 3), "topics": topics, "irritation": irritation, "hits": hits}
