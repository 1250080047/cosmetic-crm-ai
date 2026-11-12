import os
import unittest
import ast
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from assistant_service import (
    ChatStrategyRequest, ChatTurn, ModelAnswer, StrategyProposal,
    answer_request, requested_k, store_context, validate_answer, metric_summary,
)
from customer_language import cashier_script, normalize_text, segment_label

CLUSTERS = [{
    "cluster_id": 3, "name": "Khách Ngủ Đông / Rời Bỏ", "count": 7, "percentage": 100,
    "monetary_spent": 150000, "frequency_orders": 2, "recency_days": 180,
}]
METRICS = {"k_range": [2, 3, 4], "optimal_k_math": 2, "best_silhouette": 0.4, "total_customers": 7}
CONTEXT = {
    "orders": {"invoice_count": 7, "revenue": 900000},
    "strategies": [], "best_sellers": [], "unavailable": [],
    "catalog": [{"id": 83, "product_name": "Sữa rửa mặt Cetaphil 237ml", "unit_price": 100000,
                 "stock_quantity": 5, "is_active": True}],
}


class AssistantServiceTest(unittest.TestCase):
    def test_existing_ai_api_contract_survives_assistant_refactor(self):
        tree = ast.parse(Path(__file__).resolve().parents[1].joinpath("main.py").read_text(encoding="utf-8"))
        routes = set()
        for definition in tree.body:
            if not isinstance(definition, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            for decorator in definition.decorator_list:
                if isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Attribute) and decorator.args:
                    if isinstance(decorator.func.value, ast.Name) and decorator.func.value.id == "app":
                        routes.add((decorator.func.attr, decorator.args[0].value))
        # Contract used by Spring (AdminAiController, AiIntegrationService) for the base-model workflow.
        self.assertTrue({("get", "/api/ai/model-status"), ("get", "/api/ai/active-clusters"),
                         ("get", "/api/ai/training-options"), ("post", "/api/ai/train-preview"),
                         ("post", "/api/ai/deploy-model"), ("post", "/api/ai/rescore"),
                         ("get", "/api/ai/drift"), ("post", "/api/ai/drift-check"),
                         ("post", "/api/ai/predict"), ("post", "/api/ai/chat-strategy")}.issubset(routes))

    def proposal(self, **overrides):
        values = dict(segment_id=3, segment_name="nhóm bất kỳ", campaign_title="Chăm sóc khách hàng",
                      discount_percent=5, gift_description="", cashier_script="Ưu đãi của nhóm ngủ đông",
                      rationale="Theo số liệu đang có")
        values.update(overrides)
        return StrategyProposal(**values)

    def test_customer_speech_contains_offer_but_not_internal_segment(self):
        speech = cashier_script(8, "Mặt nạ dưỡng ẩm", "Dạ em chào chị! Nhóm Khách Ngủ Đông / Rời Bỏ")
        self.assertIn("8%", speech)
        self.assertIn("Mặt nạ dưỡng ẩm", speech)
        for word in ("ngu dong", "roi bo", "phan khuc", "nhom"):
            self.assertNotIn(word, normalize_text(speech))
        self.assertEqual(segment_label("Khách Ngủ Đông / Rời Bỏ"), "Khách hàng ngủ đông")

    def test_segment_label_keeps_current_names_and_upgrades_old_short_names(self):
        for name in ("Khách hàng có nguy cơ rời bỏ", "Khách hàng mới", "Khách hàng ngủ đông · chi tiêu cao", "Chưa mua hàng"):
            self.assertEqual(segment_label(name), name)
        self.assertEqual(segment_label("Lâu chưa ghé"), "Khách hàng ngủ đông")
        self.assertEqual(segment_label("Mới quen"), "Khách hàng mới")
        self.assertEqual(segment_label("Mua đều · chi tiêu cao"), "Khách hàng trung thành · chi tiêu cao")
        self.assertEqual(segment_label("Khách mới"), "Chưa mua hàng")

    def test_parsing_k_does_not_confuse_discount_or_group_id_with_cluster_count(self):
        self.assertEqual(requested_k(ChatStrategyRequest(message="Giảm 5%", k=4)), 4)
        self.assertEqual(requested_k(ChatStrategyRequest(message="Chia thành 5 nhóm", k=4)), 5)
        self.assertEqual(requested_k(ChatStrategyRequest(message="Nhóm số 3 có gì?", k=4)), 4)

    def test_invalid_segment_or_nonexistent_gift_is_not_actionable(self):
        answer = ModelAnswer(reply="Đề xuất", proposals=[
            self.proposal(segment_id=99), self.proposal(gift_product_id=999),
        ])
        self.assertEqual(validate_answer(answer, CLUSTERS, CONTEXT, [2, 3, 4]).proposals, [])

    def test_actual_gift_name_is_bound_to_actual_catalog_id(self):
        answer = ModelAnswer(reply="Đề xuất", proposals=[self.proposal(gift_product_id=83, gift_description="quà tự bịa")])
        result = validate_answer(answer, CLUSTERS, CONTEXT, [2, 3, 4])
        proposal = result.proposals[0]
        self.assertEqual(proposal.gift_description, "Tặng 1 Sữa rửa mặt Cetaphil 237ml")
        self.assertEqual(proposal.segment_name, "Khách hàng ngủ đông")
        self.assertNotIn("nhom", normalize_text(proposal.cashier_script))

    def test_unlinked_invented_gift_is_removed_without_fabricating_a_replacement(self):
        answer = ModelAnswer(reply="Đề xuất", proposals=[self.proposal(gift_description="Voucher tự bịa")])
        result = validate_answer(answer, CLUSTERS, CONTEXT, [2, 3, 4]).proposals[0]
        self.assertEqual(result.gift_description, "")
        self.assertNotIn("Voucher", result.cashier_script)

    def test_unsupported_profit_guarantee_is_replaced_with_factual_review_requirements(self):
        answer = ModelAnswer(reply="Đề xuất", proposals=[self.proposal(rationale="Ưu đãi chắc chắn đảm bảo lợi nhuận.")])
        result = validate_answer(answer, CLUSTERS, CONTEXT, [2, 3, 4]).proposals[0]
        self.assertNotIn("đảm bảo lợi nhuận", result.rationale)
        self.assertIn("7 khách", result.rationale)
        self.assertIn("Cần kiểm tra giá vốn", result.rationale)

    @patch.dict(os.environ, {"GEMINI_ALLOW_STORE_CONTEXT": "0"})
    @patch("assistant_service.store_context", return_value=CONTEXT)
    def test_internal_mode_never_calls_external_provider(self, _):
        client = Mock()
        result = answer_request(ChatStrategyRequest(message="Doanh thu hiện có?", k=2), CLUSTERS, METRICS, None, client)
        client.models.generate_content.assert_not_called()
        self.assertEqual(result.source, "data")
        self.assertIn("900.000", result.reply)
        self.assertEqual(result.proposals, [])

    @patch.dict(os.environ, {"GEMINI_ALLOW_STORE_CONTEXT": "1"})
    @patch("assistant_service.store_context", return_value=CONTEXT)
    def test_conversation_history_is_forwarded_and_plain_answers_do_not_create_promotions(self, _):
        client = Mock()
        client.models.generate_content.return_value = SimpleNamespace(
            text='{"reply":"Tổng thực thu là 900.000 đồng.","proposals":[],"suggested_k":null}')
        request = ChatStrategyRequest(message="Vậy nguồn là gì?", k=2, history=[
            ChatTurn(role="user", content="Doanh thu bao nhiêu?"),
            ChatTurn(role="assistant", content="900.000 đồng từ hóa đơn đã lưu."),
        ])
        result = answer_request(request, CLUSTERS, METRICS, None, client)
        self.assertEqual(result.source, "gemini")
        self.assertEqual(result.proposals, [])
        contents = client.models.generate_content.call_args.kwargs["contents"]
        self.assertEqual([content.role for content in contents], ["user", "user", "model", "user"])
        self.assertIn("900.000", contents[2].parts[0].text)
        self.assertIn("avg_customer_lifetime_spend_vnd", contents[0].parts[0].text)
        self.assertIn("TRUNG BÌNH MỖI KHÁCH", contents[0].parts[0].text)

    @patch.dict(os.environ, {"GEMINI_ALLOW_STORE_CONTEXT": "1"})
    @patch("assistant_service.store_context", return_value=CONTEXT)
    def test_invalid_provider_output_degrades_to_real_data_without_canned_campaign(self, _):
        client = Mock()
        client.models.generate_content.return_value = SimpleNamespace(text="not json")
        result = answer_request(ChatStrategyRequest(message="Doanh thu?", k=2), CLUSTERS, METRICS, None, client)
        self.assertEqual(result.source, "data")
        self.assertIn("900.000", result.reply)
        self.assertEqual(result.proposals, [])
        self.assertLessEqual(client.models.generate_content.call_count, 2)

    def test_metrics_are_sent_per_k_so_the_current_score_cannot_be_misread(self):
        metrics = {"k_range": [2, 3, 6, 7], "elbow_wcss": [9, 8, 5, 4], "silhouette_scores": [0.41, 0.31, 0.2844, 0.2814],
                   "smallest_group_percent": [40, 18, 9.4, 9.9], "optimal_k_math": 2, "recommended_k": 3}
        summary = metric_summary(metrics, 6)
        self.assertEqual(summary["current_k_separation_score"], 0.2844)
        row = next(item for item in summary["k_options"] if item["k"] == 7)
        self.assertEqual(row["separation_score_silhouette"], 0.2814)
        self.assertEqual(sum(item["is_current"] for item in summary["k_options"]), 1)

    @patch.dict(os.environ, {"GEMINI_ALLOW_STORE_CONTEXT": "0"})
    @patch("assistant_service.store_context")
    def test_strategy_question_without_ai_lists_current_offers_and_their_status(self, context):
        context.return_value = {**CONTEXT, "strategies": [
            {"segment_id": 3, "title": "Chào mừng trở lại", "discount_percent": 5, "gift_description": "", "is_active": False}]}
        result = answer_request(ChatStrategyRequest(message="Đề xuất ưu đãi có quà tặng cho từng nhóm", k=2),
                                CLUSTERS, METRICS, None, Mock())
        self.assertEqual(result.source, "data")
        self.assertIn("Chào mừng trở lại", result.reply)
        self.assertIn("TẮT", result.reply)
        self.assertNotIn("Cetaphil", result.reply)

    @patch.dict(os.environ, {"GEMINI_ALLOW_STORE_CONTEXT": "1"})
    @patch("assistant_service.store_context", return_value=CONTEXT)
    def test_missing_groups_and_unfounded_profit_claims_are_flagged(self, _):
        clusters = CLUSTERS + [{**CLUSTERS[0], "cluster_id": 4, "name": "VIP"}]
        client = Mock()
        client.models.generate_content.return_value = SimpleNamespace(text=(
            '{"reply":"Giữ biên lợi nhuận tốt.","suggested_k":null,"proposals":[{"segment_id":3,"segment_name":"x",'
            '"campaign_title":"Quay lại","discount_percent":5,"gift_description":"","cashier_script":"","rationale":""}]}'))
        result = answer_request(ChatStrategyRequest(message="Đề xuất ưu đãi cho từng nhóm", k=2), clusters, METRICS, None, client)
        self.assertEqual(result.source, "gemini")
        self.assertIn("VIP", result.notice)
        self.assertIn("giá vốn", result.notice)
        payload = client.models.generate_content.call_args.kwargs["contents"][0].parts[0].text
        self.assertIn('"cost_price_available": false', payload)
        self.assertIn("k_options", payload)

    @patch.dict(os.environ, {"GEMINI_ALLOW_STORE_CONTEXT": "1"})
    @patch("assistant_service.time.sleep")
    @patch("assistant_service.store_context", return_value=CONTEXT)
    def test_busy_provider_is_retried_before_falling_back(self, _, sleep):
        busy = Exception("busy"); busy.code = 503
        client = Mock()
        client.models.generate_content.side_effect = [busy, SimpleNamespace(
            text='{"reply":"Đã phân tích.","proposals":[],"suggested_k":null}')]
        result = answer_request(ChatStrategyRequest(message="Doanh thu?", k=2), CLUSTERS, METRICS, None, client)
        self.assertEqual(result.source, "gemini")
        self.assertEqual(client.models.generate_content.call_count, 2)
        sleep.assert_called_once()

    def test_context_queries_are_read_only_and_do_not_select_customer_identity(self):
        queries = []
        def read(_, query):
            queries.append(query.lower().strip())
            return []
        with patch("assistant_service._records", side_effect=read):
            store_context(None)
        self.assertEqual(len(queries), 4)
        self.assertTrue(all(query.startswith("select") for query in queries))
        self.assertTrue(all("phone" not in query and "full_name" not in query for query in queries))


if __name__ == "__main__":
    unittest.main()
