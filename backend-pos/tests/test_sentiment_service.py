"""Vietnamese review sentiment: negation, missing accents, words that collide without accents, skin reactions."""
import unittest

from sentiment_service import analyze, strip_accents


class SentimentTest(unittest.TestCase):
    def check(self, text, rating, sentiment, irritation=False):
        result = analyze(text, rating)
        self.assertEqual(result["sentiment"], sentiment, f"{text!r} → {result}")
        self.assertEqual(result["irritation"], irritation, f"{text!r} → {result}")
        return result

    def test_praise_and_complaints(self):
        self.check("Sữa rửa mặt dịu nhẹ, rất thơm, da mịn hẳn. Sẽ mua lại!", 5, "POSITIVE")
        self.check("Kem dưỡng bết dính, mùi hắc, thất vọng", 2, "NEGATIVE")
        self.check("Dùng tạm được", 3, "NEUTRAL")

    def test_negation_flips_the_meaning(self):
        self.check("Không thơm như mình nghĩ, chưa thấy hiệu quả", 3, "NEGATIVE")
        self.check("Thấm nhanh, không bết, không bị kích ứng", 4, "POSITIVE", irritation=False)
        self.check("Mình không hài lòng", None, "NEGATIVE")

    def test_reviews_typed_without_accents(self):
        self.check("rat thich, thom lau, min da", None, "POSITIVE")
        self.check("dung bi noi man do va ngua", 4, "NEGATIVE", irritation=True)

    def test_words_that_collide_without_accents_are_not_misread(self):
        # "rất" (very) is not "rát" (burning); "kem" (cream) is not "kém" (poor); "son hồng" is a colour, not "hông" (= không)
        self.check("Kem này rất tốt, son màu hồng đẹp", 5, "POSITIVE", irritation=False)
        self.check("Đạt hiệu quả như mong đợi", 5, "POSITIVE")

    def test_a_skin_reaction_is_always_a_complaint(self):
        result = self.check("Thơm nhưng bôi lên bị dị ứng, đỏ rát", 4, "NEGATIVE", irritation=True)
        self.assertIn("Kích ứng da", result["topics"])
        self.assertIn("Mùi hương", result["topics"])

    def test_topics(self):
        topics = analyze("Giá hơi đắt, chai bị móp khi nhận", 3)["topics"]
        self.assertIn("Giá", topics)
        self.assertIn("Bao bì", topics)

    def test_stars_alone_when_there_is_no_text(self):
        self.assertEqual(analyze("", 5)["sentiment"], "POSITIVE")
        self.assertEqual(analyze("", 1)["sentiment"], "NEGATIVE")
        self.assertEqual(analyze("", None)["sentiment"], "NEUTRAL")
        self.assertEqual(strip_accents("Đỏ RÁT, ngứa!"), "do rat ngua")


if __name__ == "__main__":
    unittest.main()
