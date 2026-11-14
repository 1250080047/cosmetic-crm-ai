import os
os.environ["LOKY_MAX_CPU_COUNT"] = "4"
import threading
import time
import urllib
from typing import Literal, Optional

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from google import genai
from google.genai import types
from pydantic import BaseModel, Field
from sqlalchemy import create_engine

import basket_service
import sentiment_service
from assistant_service import ChatStrategyRequest, ChatStrategyResponse, answer_request, requested_k
from clustering_service import ClusterError, ClusterStore, StaleDeployment
from customer_language import cashier_script as professional_cashier_script, segment_label
from internal_auth import require_internal_key
from settings import load_settings

load_settings()
app = FastAPI(title="Cosmetic CRM - LRFM base model", version="7.0.0", dependencies=[Depends(require_internal_key)])
app.add_middleware(CORSMiddleware, allow_origins=[], allow_credentials=False, allow_methods=["*"], allow_headers=["*"])

gemini_client = None
try:
    if not os.getenv("GEMINI_API_KEY"):
        raise ValueError("GEMINI_API_KEY chưa được cấu hình trong backend-pos/.env")
    gemini_client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"), http_options=types.HttpOptions(timeout=45000))
except Exception as error:
    print(f"--> Chưa khởi tạo Gemini: {type(error).__name__}")

params = urllib.parse.quote_plus(
    "DRIVER={ODBC Driver 17 for SQL Server};"
    f"SERVER={os.getenv('DB_SERVER', 'localhost')};DATABASE={os.getenv('DB_NAME', 'cosmetic_crm_db')};"
    f"UID={os.getenv('DB_USER', 'sa')};PWD={os.getenv('DB_PASSWORD', '')};TrustServerCertificate=yes;"
)
DB_ENGINE = create_engine(f"mssql+pyodbc:///?odbc_connect={params}", pool_pre_ping=True)
store = ClusterStore(DB_ENGINE)


def _errors(action):
    try:
        return action()
    except StaleDeployment as error:
        raise HTTPException(409, str(error)) from error
    except ClusterError as error:
        raise HTTPException(422, str(error)) from error
    except HTTPException:
        raise
    except Exception as error:
        print(f"--> Lỗi dịch vụ phân cụm: {type(error).__name__}: {error}")
        raise HTTPException(503, "Chưa đọc/ghi được dữ liệu phân cụm trong SQL Server. Dữ liệu cũ được giữ nguyên.") from error


def _daily_rescore():
    """Step 6: R and L grow every day, so every customer is placed on the ruler again once a day."""
    while True:
        try:
            if store.rescore_if_stale():
                print("--> Đã xếp lại nhóm khách hàng cho ngày mới.")
        except Exception as error:
            print(f"--> Chưa xếp lại nhóm khách hàng: {type(error).__name__}")
        time.sleep(1800)


def _refresh_group_names():
    """Labels of the active version follow the current naming rules (the learned ruler is untouched)."""
    try:
        result = store.refresh_names()
        for change in result.get("changes", []):
            print(f"--> Đổi tên nhóm {change['cluster_id']}: {change['old']} → {change['new']}")
    except Exception as error:
        print(f"--> Chưa cập nhật được tên nhóm: {type(error).__name__}: {error}")


def _first_basket_rules():
    """Products bought together: computed once when the database has no rules yet (later from the admin page)."""
    try:
        if not basket_service.has_rules(DB_ENGINE):
            result = basket_service.compute(DB_ENGINE, "Tự tính khi khởi động")
            print(f"--> Đã tìm {result['run']['rule_count']} quy tắc mua kèm.")
    except Exception as error:
        print(f"--> Chưa tính được quy tắc mua kèm: {type(error).__name__}: {error}")


_refresh_group_names()
threading.Thread(target=_daily_rescore, daemon=True, name="daily-rescore").start()
threading.Thread(target=_first_basket_rules, daemon=True, name="basket-rules").start()


# ---- model state --------------------------------------------------------------------------------
@app.get("/api/ai/model-status")
def model_status():
    return _errors(store.status)


@app.get("/api/ai/active-clusters")
def active_clusters():
    return _errors(store.active)


@app.get("/api/ai/training-options")
def training_options(source: Literal["base", "extended"] = "base"):
    return _errors(lambda: store.training_options(source))


@app.get("/api/ai/base-analysis")
def base_analysis(k: int = Query(..., ge=2, le=10)):
    """The base data split into k groups, for comparison on the overview page (nothing is deployed)."""
    return _errors(lambda: store.base_analysis(k))


@app.post("/api/ai/train-preview")
def train_preview(k: int = Query(..., ge=2, le=10), source: Literal["base", "extended"] = "base"):
    return _errors(lambda: store.preview(k, source))


class DeployRequest(BaseModel):
    preview_id: str = Field(min_length=8, max_length=64)
    expected_version: Optional[str] = Field(default=None, max_length=64)
    reason: str = Field(default="", max_length=500)
    user: str = Field(default="", max_length=100)


@app.post("/api/ai/deploy-model")
def deploy_model(request: DeployRequest):
    if os.getenv("AI_READ_ONLY_PREVIEW") == "1":
        raise HTTPException(403, "Dịch vụ kiểm tra đang ở chế độ chỉ đọc.")
    return _errors(lambda: store.deploy(request.preview_id, request.expected_version, request.reason, request.user))


@app.post("/api/ai/rescore")
def rescore():
    return _errors(store.rescore)


@app.get("/api/ai/drift")
def drift():
    return _errors(lambda: store.drift(record=False))


@app.post("/api/ai/drift-check")
def drift_check():
    return _errors(lambda: store.drift(record=True))


# ---- counter (step 5) ----------------------------------------------------------------------------
class PredictRequest(BaseModel):
    length: float = Field(ge=0, allow_inf_nan=False)
    recency: float = Field(ge=0, allow_inf_nan=False)
    frequency: float = Field(ge=0, allow_inf_nan=False)
    monetary: float = Field(ge=0, allow_inf_nan=False)
    has_purchase: bool


@app.post("/api/ai/predict")
def predict_segment(req: PredictRequest):
    cluster = _errors(lambda: store.predict(req.length, req.recency, req.frequency, req.monetary, req.has_purchase))
    # Approved benefits are read from strategies by Spring, never inferred here.
    return {"cluster_id": cluster["cluster_id"], "segment_name": segment_label(cluster["name"]),
            "color_code": cluster["color"], "active_version": cluster["active_version"],
            "discount_percent": 0, "gift_description": "", "cashier_script": professional_cashier_script()}


# ---- products bought together (market basket analysis) -------------------------------------------
def _basket(action):
    try:
        return action()
    except Exception as error:
        print(f"--> Lỗi phân tích giỏ hàng: {type(error).__name__}: {error}")
        raise HTTPException(503, "Chưa đọc/ghi được dữ liệu mua kèm trong SQL Server. Kết quả cũ được giữ nguyên.") from error


@app.get("/api/ai/associations")
def associations(limit: int = Query(300, ge=1, le=2000)):
    return _basket(lambda: basket_service.latest(DB_ENGINE, limit))


class RecomputeRequest(BaseModel):
    user: str = Field(default="", max_length=100)


@app.post("/api/ai/associations/recompute")
def recompute_associations(request: RecomputeRequest):
    if os.getenv("AI_READ_ONLY_PREVIEW") == "1":
        raise HTTPException(403, "Dịch vụ kiểm tra đang ở chế độ chỉ đọc.")
    return _basket(lambda: basket_service.compute(DB_ENGINE, request.user))


# ---- product reviews: sentiment (runs locally, the text never leaves this computer) --------------
class ReviewRequest(BaseModel):
    text: str = Field(default="", max_length=1000)
    rating: Optional[int] = Field(default=None, ge=1, le=5)


@app.post("/api/ai/review-sentiment")
def review_sentiment(request: ReviewRequest):
    return sentiment_service.analyze(request.text, request.rating)


# ---- assistant -----------------------------------------------------------------------------------
def _simulated_clusters(k: int) -> list:
    """Groups for another K on the base data, for discussion only (never deployed from here)."""
    return store.base_analysis(k)["clusters"]


@app.post("/api/ai/chat-strategy", response_model=ChatStrategyResponse)
def chat_strategy_advisor(req: ChatStrategyRequest):
    state = _errors(store.status)
    target_k = requested_k(req)
    using_active = state["has_model"] and target_k == state["active_k"]
    clusters = state["clusters"] if using_active else _errors(lambda: _simulated_clusters(target_k))
    metrics = _errors(lambda: store.training_options("base"))
    model_context = None
    if state["has_model"]:
        try:
            report = store.drift(record=False)
        except Exception:
            report = None
        model_context = {
            "model_label": state["label"], "reference_date": state["reference_date"],
            "training_source": state["training_source"], "today": state["today"],
            "customers_without_purchase": state["new_customers"],
            "current_customers_by_group": [{"name": segment_label(c["name"]), "current_count": c["current_count"]}
                                           for c in state["clusters"]],
            "drift_status": report and {"status": report["status"], "message": report["message"],
                                        "active_customers_12m": report["sample_size"], "reasons": report["reasons"]},
        }
    result = answer_request(req, clusters, metrics, DB_ENGINE, gemini_client, model_context)
    return result.model_copy(update={"analysis_k": target_k,
                                     "active_version": state.get("active_version") if using_active else None})
