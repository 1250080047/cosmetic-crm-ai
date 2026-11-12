"""LRFM customer clustering with a frozen base model ("thước đo cũ").

Steps (numbered as in the thesis):
 1. Base data: the OnlineSales history, frozen at BASE_REFERENCE_DATE (the day after its last order).
 2. Training (the ONLY step where the model learns): customers who bought in the 365 days before the
    reference date. L, F, M are capped at their 99th percentile, log-transformed, then standardised with the
    mean/std of these customers. K-means (k-means++, random_state 42) learns the group centres.
 3. Every customer, old or new, is measured on any day D with the same definitions:
      L = D - first purchase, R = D - last purchase,
      F = number of invoices and M = money spent in the 365 days up to D.
 4. The customer is "placed on the old ruler": z = (log(min(x, cap)) - mean_v) / std_v with the STORED
    caps/mean/std, then the nearest stored centre. Nothing is re-learned, so adding customers never moves groups.
    No purchase yet -> "Chưa mua hàng" (outside the groups). No purchase in 365 days -> the base model's lapsed group.
 5. At the counter Spring computes the customer's LRFM in SQL and asks for the group (predict).
 6. Re-scoring: all customers are placed on the ruler again (daily / on demand) because R and L grow every day.
 7. Drift: customers active today are compared with the base ruler (average z per feature, share outside the
    learned range, group shares). Drift is only judged with enough customers.
 8. Controlled retraining: the admin previews a new version (base data + today's active customers), sees the
    new ruler, quality metrics and the old -> new transition matrix, and confirms. Old versions stay archived.
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import uuid
from collections import Counter
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.metrics import davies_bouldin_score, silhouette_score
from sqlalchemy import text

from customer_language import (ABOUT_TO_SLEEP, AT_RISK, HIBERNATING, LOYAL, NEED_ATTENTION, NEW, NO_PURCHASE,
                               POTENTIAL, PROMISING, VIP)

FEATURES = ["Length", "Recency", "Frequency", "Monetary"]
FEATURE_VI = {"Length": "Gắn bó (L, ngày)", "Recency": "Lần mua gần nhất (R, ngày)",
              "Frequency": "Số đơn 12 tháng (F)", "Monetary": "Chi tiêu 12 tháng (M, đ)"}
CAPPED = ("Length", "Frequency", "Monetary")
LOWER = {"Length": 0.0, "Recency": 0.0, "Frequency": 1.0, "Monetary": 1000.0}
WINDOW_DAYS = 365
CAP_QUANTILE = 0.99
NEW_CUSTOMER = -1
LOCK_RESOURCE = "cosmetic-crm:cluster-model"
COLORS = ["#94a3b8", "#8b9e71", "#5e9a83", "#4b9b9a", "#4c92b2",
          "#6578bb", "#8670ad", "#ab7796", "#ba9361", "#d2a638"]
SPEND_LEVELS = ["chi tiêu thấp", "chi tiêu vừa", "chi tiêu cao"]
VIP_MAX_SHARE = 25.0
MIN_OVERLAP = 0.6           # an approved offer survives retraining when >= 60% of its group is unchanged (Jaccard)
MIN_GROUP_PERCENT = 5.0
SILHOUETTE_TOLERANCE = 0.01
SILHOUETTE_SAMPLE = 5000
DRIFT_MIN_SAMPLE = 30       # fewer active customers than this: too early to judge drift
DRIFT_MEAN_Z = 0.5          # average shift of half a base standard deviation
DRIFT_GROUP_SHARE = 60.0    # one group swallowing most of today's customers
DRIFT_OUTSIDE_SHARE = 20.0  # share of customers beyond the learned range of a feature


def base_reference_date() -> date:
    return date.fromisoformat(os.getenv("BASE_REFERENCE_DATE", "2021-01-12"))


class ClusterError(ValueError):
    pass


class StaleDeployment(ClusterError):
    pass


def vn_number(value: float, digits: int = 0) -> str:
    return f"{float(value):,.{digits}f}".replace(",", "_").replace(".", ",").replace("_", ".")


def vn_date(value) -> str:
    return pd.Timestamp(value).strftime("%d/%m/%Y")


# ---------------------------------------------------------------------------------------------
# Step 3: measuring customers on day D
# ---------------------------------------------------------------------------------------------
def window_bounds(as_of: date) -> tuple[datetime, datetime]:
    """Invoices counted for F/M: the 365 calendar days ending on as_of (inclusive)."""
    start = datetime.combine(as_of - timedelta(days=WINDOW_DAYS - 1), datetime.min.time())
    end = datetime.combine(as_of + timedelta(days=1), datetime.min.time())
    return start, end


def lrfm_from_rows(frame: pd.DataFrame, as_of: date) -> pd.DataFrame:
    day = pd.Timestamp(as_of)
    first = pd.to_datetime(frame["first_purchase"], errors="coerce").dt.normalize()
    last = pd.to_datetime(frame["last_purchase"], errors="coerce").dt.normalize()
    result = pd.DataFrame({"CustomerID": pd.to_numeric(frame["CustomerID"]).astype("int64")})
    result["HasPurchase"] = last.notna().to_numpy()
    result["Length"] = (day - first).dt.days.clip(lower=0).fillna(0).astype(float).to_numpy()
    result["Recency"] = (day - last).dt.days.clip(lower=0).fillna(0).astype(float).to_numpy()
    result["Frequency"] = pd.to_numeric(frame["Frequency"], errors="coerce").fillna(0).astype(float).to_numpy()
    result["Monetary"] = pd.to_numeric(frame["Monetary"], errors="coerce").fillna(0).astype(float).to_numpy()
    if result["CustomerID"].duplicated().any():
        raise ClusterError("Dữ liệu có mã khách hàng trùng nhau.")
    if not np.isfinite(result[FEATURES].to_numpy()).all():
        raise ClusterError("Dữ liệu LRFM có giá trị không hữu hạn.")
    return result.sort_values("CustomerID").reset_index(drop=True)


def load_lrfm(connection, as_of: date, lock_hint: str = "") -> pd.DataFrame:
    """L, R, F, M of every customer as of the given day; invoices after that day are ignored."""
    start, end = window_bounds(as_of)
    frame = pd.read_sql(text(
        "SELECT c.customer_id AS CustomerID, MIN(o.order_date) AS first_purchase, MAX(o.order_date) AS last_purchase, "
        "SUM(CASE WHEN o.order_date >= :start THEN 1 ELSE 0 END) AS Frequency, "
        "SUM(CASE WHEN o.order_date >= :start THEN o.final_amount ELSE 0 END) AS Monetary "
        "FROM customers c" + lock_hint + " LEFT JOIN orders o ON o.customer_id = c.customer_id AND o.order_date < :end "
        "GROUP BY c.customer_id ORDER BY c.customer_id"), connection, params={"start": start, "end": end})
    return lrfm_from_rows(frame, as_of)


def active_population(frame: pd.DataFrame) -> pd.DataFrame:
    """Customers with at least one invoice inside the 365-day window."""
    return frame[frame.HasPurchase & (frame.Frequency > 0) & (frame.Monetary > 0)].copy()


# ---------------------------------------------------------------------------------------------
# Step 2 and 4: the ruler
# ---------------------------------------------------------------------------------------------
def learn_caps(training: pd.DataFrame) -> dict:
    return {name: float(training[name].quantile(CAP_QUANTILE)) for name in CAPPED}


def log_features(frame: pd.DataFrame, caps: dict) -> pd.DataFrame:
    values = frame[FEATURES].astype(float).clip(lower=LOWER)
    for name, upper in caps.items():
        values[name] = values[name].clip(upper=max(float(upper), LOWER[name]))
    return np.log1p(values)


def zscores(model: dict, frame: pd.DataFrame) -> pd.DataFrame:
    mean = pd.Series(model["mean"])[FEATURES]
    std = pd.Series(model["std"])[FEATURES]
    return (log_features(frame, model["caps"]) - mean) / std


def score(model: dict, frame: pd.DataFrame) -> np.ndarray:
    """Group of every row on the stored ruler: -1 = no purchase yet, lapsed group = nothing in 365 days."""
    labels = np.full(len(frame), NEW_CUSTOMER, dtype=int)
    if not len(frame):
        return labels
    active = (frame.HasPurchase & (frame.Frequency > 0)).to_numpy()
    lapsed = (frame.HasPurchase & (frame.Frequency <= 0)).to_numpy()
    if active.any():
        z = zscores(model, frame[active]).to_numpy()
        centres = np.asarray(model["centroids"], dtype=float)
        distances = ((z[:, None, :] - centres[None, :, :]) ** 2).sum(axis=2)
        labels[active] = distances.argmin(axis=1)
    labels[lapsed] = int(model["lapsed_cluster_id"])
    return labels


def score_one(model: dict, length: float, recency: float, frequency: float, monetary: float,
              has_purchase: bool) -> int:
    row = pd.DataFrame([{"CustomerID": 0, "HasPurchase": bool(has_purchase), "Length": length,
                         "Recency": recency, "Frequency": frequency, "Monetary": monetary}])
    if not np.isfinite(row[FEATURES].to_numpy(dtype=float)).all():
        raise ClusterError("Chỉ số LRFM không hợp lệ.")
    return int(score(model, row)[0])


# ---------------------------------------------------------------------------------------------
# Group names
# ---------------------------------------------------------------------------------------------
def naming_thresholds(training: pd.DataFrame) -> dict:
    """Named relative to the training customers instead of fixed day/order cut-offs."""
    q = lambda name, share: float(training[name].quantile(share))
    return {"recent_days": q("Recency", 1 / 3), "lapsed_days": q("Recency", 2 / 3),
            "frequent_orders": max(q("Frequency", 0.75), 2.0), "new_length_days": q("Length", 1 / 3),
            "spend_low": q("Monetary", 1 / 3), "spend_high": q("Monetary", 2 / 3)}


def _base_name(row, limits: dict, is_top: bool, share: float) -> str:
    """Name of a group from its typical (median) customer; the names follow the usual RFM segment vocabulary."""
    recent = row.RecencyMedian <= limits["recent_days"]
    lapsed = row.RecencyMedian >= limits["lapsed_days"]
    frequent = row.FrequencyMedian >= limits["frequent_orders"]
    young = row.LengthMedian <= limits["new_length_days"]
    if is_top and frequent and not lapsed and share <= VIP_MAX_SHARE:
        return VIP
    if lapsed:
        return AT_RISK if frequent else HIBERNATING
    if young and not frequent:  # L is what RFM cannot see: a short relationship that is still active
        return NEW
    if recent:
        if frequent:
            return LOYAL
        return POTENTIAL if row.MonetaryMedian >= limits["spend_high"] else PROMISING
    return NEED_ATTENTION if frequent else ABOUT_TO_SLEEP


def _unique_names(grouped: pd.DataFrame, bases: list, limits: dict) -> list:
    names = list(bases)
    for base, total in Counter(bases).items():
        if total == 1:
            continue
        positions = [index for index, value in enumerate(bases) if value == base]
        levels = [0 if grouped.loc[i, "MonetaryMedian"] < limits["spend_low"]
                  else (2 if grouped.loc[i, "MonetaryMedian"] >= limits["spend_high"] else 1) for i in positions]
        if len(set(levels)) == len(levels):
            qualifiers = [SPEND_LEVELS[level] for level in levels]
        elif total == 2:
            qualifiers = ["chi tiêu thấp hơn", "chi tiêu cao hơn"]
        elif total == 3:
            qualifiers = list(SPEND_LEVELS)
        else:
            qualifiers = [f"chi tiêu mức {number}/{total}" for number in range(1, total + 1)]
        for index, qualifier in zip(positions, qualifiers):
            names[index] = base + " · " + qualifier
    return names


def _cluster_names(grouped: pd.DataFrame, limits: dict, total: int) -> list:
    """grouped: one row per group in rank order (lowest to highest spend) with the four medians and count."""
    last = len(grouped) - 1
    bases = [_base_name(row, limits, rank == last, row.count / total * 100) for rank, row in enumerate(grouped.itertuples())]
    return _unique_names(grouped, bases, limits)


def renamed_clusters(model: dict) -> list:
    """The stored groups named again with the current rules, from the medians saved in the version."""
    grouped = pd.DataFrame([{"LengthMedian": c["length_median"], "RecencyMedian": c["recency_median"],
                             "FrequencyMedian": c["frequency_median"], "MonetaryMedian": c["monetary_median"],
                             "count": c["count"]} for c in model["clusters"]])
    names = _cluster_names(grouped, model["naming_thresholds"], model["training_rows"])
    return [{**cluster, "name": name} for cluster, name in zip(model["clusters"], names)]


def offer_title(name: str) -> str:
    return "Ưu đãi — " + name


def _distribution(frame: pd.DataFrame) -> dict:
    return {name: {"min": float(frame[name].min()), "p25": float(frame[name].quantile(.25)),
                   "median": float(frame[name].median()), "p75": float(frame[name].quantile(.75)),
                   "max": float(frame[name].max()), "mean": float(frame[name].mean())} for name in FEATURES}


# ---------------------------------------------------------------------------------------------
# Step 2 / 8: learning a version
# ---------------------------------------------------------------------------------------------
def _ruler(training: pd.DataFrame) -> tuple[dict, pd.Series, pd.Series, np.ndarray]:
    caps = learn_caps(training)
    logged = log_features(training, caps)
    mean, std = logged.mean(), logged.std(ddof=0).replace(0, 1.0)
    return caps, mean, std, ((logged - mean) / std).to_numpy()


def fit_model(training: pd.DataFrame, k: int, reference_date: date, source: str) -> dict:
    if not isinstance(k, int) or not 2 <= k <= 10:
        raise ClusterError("Chọn từ 2 đến 10 nhóm.")
    training = training.reset_index(drop=True).copy()
    if len(training) < max(k, 10):
        raise ClusterError("Không đủ khách hàng để huấn luyện mô hình.")
    caps, mean, std, z = _ruler(training)
    if len(np.unique(z.round(9), axis=0)) < k:
        raise ClusterError("Không đủ khách hàng có hành vi khác nhau để tạo đúng số nhóm đã chọn.")
    kmeans = KMeans(n_clusters=k, init="k-means++", random_state=42, n_init=10)
    raw = kmeans.fit_predict(z)
    if len(np.unique(raw)) != k:
        raise ClusterError("Mô hình chưa tạo đủ số nhóm riêng biệt. Hãy chọn số nhóm nhỏ hơn.")
    training["raw_id"] = raw
    grouped = training.groupby("raw_id").agg(
        **{f"{n}": (n, "mean") for n in FEATURES}, **{f"{n}Median": (n, "median") for n in FEATURES},
        count=("CustomerID", "size")).reset_index()
    grouped = grouped.sort_values(["Monetary", "Frequency", "Recency", "raw_id"], kind="stable").reset_index(drop=True)
    limits = naming_thresholds(training)
    names = _cluster_names(grouped, limits, len(training))
    mapping = {int(row.raw_id): rank for rank, row in enumerate(grouped.itertuples())}
    clusters = []
    for rank, row in enumerate(grouped.itertuples()):
        clusters.append({
            "cluster_id": rank, "rank": rank + 1, "name": names[rank], "color": COLORS[round(rank * (len(COLORS) - 1) / max(k - 1, 1))],
            "count": int(row.count), "percentage": round(row.count / len(training) * 100, 2),
            "length_days": float(row.Length), "recency_days": float(row.Recency),
            "frequency_orders": float(row.Frequency), "monetary_spent": float(row.Monetary),
            "length_median": float(row.LengthMedian), "recency_median": float(row.RecencyMedian),
            "frequency_median": float(row.FrequencyMedian), "monetary_median": float(row.MonetaryMedian),
            "behavior_desc": (f"Khách điển hình: gắn bó {vn_number(row.LengthMedian)} ngày, "
                              f"{vn_number(row.RecencyMedian)} ngày chưa quay lại, "
                              f"{vn_number(row.FrequencyMedian)} đơn và chi {vn_number(row.MonetaryMedian)} đ trong 12 tháng."),
        })
    canonical = np.array([mapping[int(label)] for label in raw])
    centroids = np.zeros_like(kmeans.cluster_centers_)
    for raw_id, rank in mapping.items():
        centroids[rank] = kmeans.cluster_centers_[raw_id]
    sample = min(len(z), SILHOUETTE_SAMPLE)
    lapsed = max(clusters, key=lambda c: (c["recency_median"], -c["monetary_median"]))["cluster_id"]
    return {
        "version": uuid.uuid4().hex, "k": k, "features": FEATURES, "window_days": WINDOW_DAYS,
        "reference_date": str(reference_date), "training_source": source, "training_rows": len(training),
        "cap_quantile": CAP_QUANTILE, "caps": caps, "lower": LOWER,
        "mean": {n: float(mean[n]) for n in FEATURES}, "std": {n: float(std[n]) for n in FEATURES},
        "centroids": centroids.round(6).tolist(), "clusters": clusters, "lapsed_cluster_id": int(lapsed),
        "naming_thresholds": limits, "training_distribution": _distribution(training),
        "metrics": {
            "silhouette": round(float(silhouette_score(z, canonical, sample_size=sample, random_state=42)), 4),
            "davies_bouldin": round(float(davies_bouldin_score(z, canonical)), 4),
            "inertia": round(float(kmeans.inertia_), 1),
        },
        "created_at": _now().isoformat(),
    }


def evaluate_k(training: pd.DataFrame) -> dict:
    """Quality of K = 2..10 on ONE ruler (mean/std do not depend on K)."""
    _, _, _, z = _ruler(training)
    maximum = min(10, len(training) - 1, len(np.unique(z.round(9), axis=0)))
    if maximum < 2:
        raise ClusterError("Chưa đủ dữ liệu để đánh giá số nhóm.")
    sample = min(len(z), SILHOUETTE_SAMPLE)
    ks, inertia, silhouettes, davies, smallest = list(range(2, maximum + 1)), [], [], [], []
    for k in ks:
        labels = KMeans(n_clusters=k, init="k-means++", random_state=42, n_init=10).fit(z)
        inertia.append(round(float(labels.inertia_), 1))
        silhouettes.append(round(float(silhouette_score(z, labels.labels_, sample_size=sample, random_state=42)), 4))
        davies.append(round(float(davies_bouldin_score(z, labels.labels_)), 4))
        smallest.append(round(float(np.bincount(labels.labels_).min() / len(z) * 100), 2))
    recommended, reason = recommend_k(ks, silhouettes, smallest)
    return {"k_range": ks, "elbow_wcss": inertia, "silhouette_scores": silhouettes, "davies_bouldin": davies,
            "smallest_group_percent": smallest, "optimal_k_math": ks[int(np.argmax(silhouettes))],
            "best_silhouette": max(silhouettes), "recommended_k": recommended, "recommended_reason": reason,
            "total_customers": len(training)}


def recommend_k(k_range, silhouettes, smallest):
    """At least 3 groups, every group >= 5% of customers, then the best separation (finer split on ties)."""
    candidates = [(k, s) for k, s, low in zip(k_range, silhouettes, smallest) if k >= 3 and low >= MIN_GROUP_PERCENT]
    if not candidates:
        return k_range[int(np.argmax(silhouettes))], "Dữ liệu chưa đủ để lọc theo cỡ nhóm; đang dùng số nhóm tách biệt cao nhất."
    top = max(score for _, score in candidates)
    chosen = max(k for k, score in candidates if score >= top - SILHOUETTE_TOLERANCE)
    reason = (f"Chia {chosen} nhóm: mỗi nhóm có ít nhất {MIN_GROUP_PERCENT:g}% khách hàng và các nhóm "
              f"tách biệt gần tốt nhất trong các lựa chọn từ 3 nhóm trở lên.")
    if k_range[int(np.argmax(silhouettes))] == 2:
        reason += " Chia 2 nhóm tách biệt cao hơn nhưng quá thô để làm ưu đãi riêng."
    return chosen, reason


# ---------------------------------------------------------------------------------------------
# Step 7: drift
# ---------------------------------------------------------------------------------------------
def drift_report(model: dict, current: pd.DataFrame, as_of: date) -> dict:
    active = active_population(current)
    sample = len(active)
    base_share = {c["cluster_id"]: c["percentage"] for c in model["clusters"]}
    names = {c["cluster_id"]: c["name"] for c in model["clusters"]}
    features, groups, reasons = [], [], []
    if sample:
        z = zscores(model, active)
        ranges = model["training_distribution"]
        for name in FEATURES:
            mean_z = float(z[name].mean())
            outside = float(((active[name] > ranges[name]["max"]) | (active[name] < ranges[name]["min"])).mean() * 100)
            flagged = abs(mean_z) > DRIFT_MEAN_Z or outside > DRIFT_OUTSIDE_SHARE
            features.append({"feature": name, "label": FEATURE_VI[name], "mean_z": round(mean_z, 3),
                             "current_median": float(active[name].median()),
                             "base_median": ranges[name]["median"], "outside_percent": round(outside, 1),
                             "flagged": flagged})
            if flagged:
                direction = "cao hơn" if mean_z > 0 else "thấp hơn"
                reasons.append(f"{FEATURE_VI[name]}: trung bình {direction} dữ liệu nền {vn_number(abs(mean_z), 2)} độ lệch chuẩn"
                               + (f", {vn_number(outside, 0)}% khách nằm ngoài khoảng đã học" if outside > DRIFT_OUTSIDE_SHARE else ""))
        counts = Counter(score(model, active).tolist())
        for cid in sorted(base_share):
            share = counts.get(cid, 0) / sample * 100
            groups.append({"cluster_id": cid, "name": names[cid], "count": counts.get(cid, 0),
                           "current_percent": round(share, 1), "base_percent": base_share[cid]})
        top = max(groups, key=lambda g: g["current_percent"])
        if top["current_percent"] > DRIFT_GROUP_SHARE and top["base_percent"] <= DRIFT_GROUP_SHARE:
            reasons.append(f"Nhóm {top['name']} chiếm {vn_number(top['current_percent'], 0)}% khách hiện tại "
                           f"(dữ liệu nền chỉ {vn_number(top['base_percent'], 0)}%)")
    enough = sample >= DRIFT_MIN_SAMPLE
    status = "insufficient" if not enough else ("drift" if reasons else "stable")
    message = {
        "insufficient": (f"Mới có {sample} khách mua trong 12 tháng tính tới {vn_date(as_of)}; cần ít nhất "
                         f"{DRIFT_MIN_SAMPLE} khách để kết luận. Khách vẫn được xếp nhóm bằng thước đo của mô hình nền."),
        "stable": "Khách hiện tại vẫn giống dữ liệu nền. Chưa cần huấn luyện lại.",
        "drift": "Khách hiện tại đã khác dữ liệu nền. Nên xem trước mô hình mới (bước 8) trước khi quyết định.",
    }[status]
    return {"status": status, "drift_detected": status == "drift", "message": message, "reasons": reasons,
            "sample_size": sample, "min_sample": DRIFT_MIN_SAMPLE, "as_of": str(as_of),
            "thresholds": {"mean_z": DRIFT_MEAN_Z, "group_share": DRIFT_GROUP_SHARE, "outside_share": DRIFT_OUTSIDE_SHARE},
            "features": features, "groups": groups, "model_version": model["version"]}


# ---------------------------------------------------------------------------------------------
# SQL-backed store
# ---------------------------------------------------------------------------------------------
def _iso(value):
    if value is None:
        return None
    return value.isoformat() if isinstance(value, (datetime, date)) else str(value)


def _now():
    """Store-local wall clock, the same clock Spring uses for invoices."""
    return datetime.now().replace(microsecond=0)


class ClusterStore:
    def __init__(self, engine, today=None):
        self.engine = engine
        self.today = today or date.today
        self.lock = threading.RLock()
        self._cache_lock = threading.RLock()
        self._cached = None
        self._previews: dict[str, dict] = {}
        self._options: dict[tuple, dict] = {}
        self._analyses: dict[tuple, dict] = {}

    # -- locking / reading ------------------------------------------------------------------
    @staticmethod
    def _acquire_lock(connection, mode="Exclusive"):
        if mode not in ("Shared", "Exclusive"):
            raise ValueError("Unsupported cluster lock mode")
        if connection.dialect.name == "mssql":
            value = connection.execute(text("""
                SET NOCOUNT ON;
                DECLARE @anchor int;
                SELECT TOP (1) @anchor=id FROM roles;
                DECLARE @result int;
                EXEC @result = sys.sp_getapplock @Resource=:resource,
                    @LockMode=:mode, @LockOwner='Transaction', @LockTimeout=15000;
                SELECT @result;
                """), {"resource": LOCK_RESOURCE, "mode": mode}).scalar_one()
            if value < 0:
                raise StaleDeployment("Một thao tác phân cụm khác đang chạy. Hãy thử lại.")

    @staticmethod
    def _active_row(connection):
        return connection.execute(text(
            "SELECT version, label, k, reference_date, training_source, training_rows, model_json, reason, created_by, "
            "created_at, activated_at FROM model_versions WHERE status='ACTIVE'")).mappings().first()

    def active_model(self, connection=None) -> dict | None:
        if connection is None:
            with self.engine.connect() as own:
                return self.active_model(own)
        row = self._active_row(connection)
        if row is None:
            return None
        with self._cache_lock:
            if not self._cached or self._cached["version"] != row["version"]:
                model = json.loads(row["model_json"])
                if model["version"] != row["version"]:
                    raise ClusterError("Phiên bản mô hình chưa khớp dữ liệu SQL.")
                self._cached = {**model, "label": row["label"]}
            return self._cached

    @staticmethod
    def _is_synchronized(connection, model: dict) -> bool:
        k = model["k"]
        expected = set(range(k))
        groups = connection.execute(text("SELECT id,segment_name,color_code FROM customer_segments")).mappings().all()
        strategy_ids = connection.execute(text("SELECT segment_id FROM strategies")).scalars().all()
        unknown = connection.execute(text(
            "SELECT COUNT(*) FROM customers WHERE segment_id IS NOT NULL AND (segment_id<0 OR segment_id>=:k)"), {"k": k}).scalar_one()
        by_id = {c["cluster_id"]: c for c in model["clusters"]}
        return (set(int(r["id"]) for r in groups) == expected and len(groups) == k
                and set(strategy_ids) == expected and len(strategy_ids) == k and unknown == 0
                and all(r["segment_name"] == by_id[int(r["id"])]["name"] and r["color_code"] == by_id[int(r["id"])]["color"]
                        for r in groups))

    # -- status ------------------------------------------------------------------------------
    def status(self) -> dict:
        with self.engine.connect() as connection:
            model = self.active_model(connection)
            versions = [dict(r) for r in connection.execute(text(
                "SELECT label, version, status, k, reference_date, training_source, training_rows, reason, created_by, "
                "created_at, activated_at, archived_at FROM model_versions ORDER BY id DESC")).mappings().all()]
            for row in versions:
                for key, value in row.items():
                    if isinstance(value, (datetime, date)):
                        row[key] = _iso(value)
            if model is None:
                return {"has_model": False, "versions": versions, "base_reference_date": str(base_reference_date()),
                        "message": "Chưa có mô hình nền. Hãy huấn luyện mô hình nền v1 trên dữ liệu OnlineSales."}
            current = {int(r["segment_id"]) if r["segment_id"] is not None else NEW_CUSTOMER: int(r["total"])
                       for r in connection.execute(text(
                           "SELECT segment_id, COUNT(*) AS total FROM customers GROUP BY segment_id")).mappings().all()}
            scored = connection.execute(text("SELECT MIN(segment_scored_at), MAX(segment_scored_at) FROM customers")).first()
            synchronized = self._is_synchronized(connection, model)
            last_drift = connection.execute(text(
                "SELECT TOP 1 checked_at, as_of_date, sample_size, drift_detected, result_json FROM drift_checks "
                "WHERE model_version=:v ORDER BY id DESC") if connection.dialect.name == "mssql" else text(
                "SELECT checked_at, as_of_date, sample_size, drift_detected, result_json FROM drift_checks "
                "WHERE model_version=:v ORDER BY id DESC LIMIT 1"), {"v": model["version"]}).mappings().first()
        total = sum(current.values()) or 1
        clusters = []
        for cluster in model["clusters"]:
            count = current.get(cluster["cluster_id"], 0)
            clusters.append({**cluster, "current_count": count, "current_percent": round(count / total * 100, 2)})
        return {
            "has_model": True, "active_version": model["version"], "label": model.get("label"), "active_k": model["k"],
            "reference_date": model["reference_date"], "training_source": model["training_source"],
            "training_rows": model["training_rows"], "window_days": model["window_days"],
            "ruler": [{"feature": n, "label": FEATURE_VI[n], "cap": model["caps"].get(n), "mean_log": model["mean"][n],
                       "std_log": model["std"][n], **model["training_distribution"][n]} for n in FEATURES],
            "metrics": model["metrics"], "clusters": clusters, "lapsed_cluster_id": model["lapsed_cluster_id"],
            "new_customers": current.get(NEW_CUSTOMER, 0), "all_customers": sum(current.values()),
            "scored_from": _iso(scored[0]) if scored else None,
            "scored_to": _iso(scored[1]) if scored else None,
            "today": str(self.today()), "synchronized": synchronized, "versions": versions,
            "last_drift_check": ({"checked_at": _iso(last_drift["checked_at"]), "as_of_date": _iso(last_drift["as_of_date"]),
                                  "sample_size": last_drift["sample_size"], "drift_detected": bool(last_drift["drift_detected"])}
                                 if last_drift else None),
        }

    def active(self) -> dict:
        """Compact view used by the chatbot and older pages."""
        state = self.status()
        if not state["has_model"]:
            return {"active_k": None, "active_version": None, "clusters": [], "synchronized": False,
                    "message": state["message"]}
        return {"active_k": state["active_k"], "active_version": state["active_version"], "label": state["label"],
                "clusters": state["clusters"], "total_customers": state["training_rows"],
                "reference_date": state["reference_date"], "new_customers": state["new_customers"],
                "synchronized": state["synchronized"]}

    # -- step 5: one customer at the counter --------------------------------------------------
    def predict(self, length: float, recency: float, frequency: float, monetary: float, has_purchase: bool) -> dict:
        model = self.active_model()
        if model is None:
            raise ClusterError("Chưa có mô hình nền. Hãy huấn luyện mô hình tại trang quản trị.")
        cid = score_one(model, length, recency, frequency, monetary, has_purchase)
        if cid == NEW_CUSTOMER:
            return {"cluster_id": NEW_CUSTOMER, "name": NO_PURCHASE, "color": "#64748b", "active_version": model["version"]}
        cluster = model["clusters"][cid]
        return {**cluster, "active_version": model["version"]}

    # -- step 6: re-scoring --------------------------------------------------------------------
    def _rescore(self, connection, model: dict, as_of: date) -> dict:
        lock_hint = " WITH (UPDLOCK,HOLDLOCK)" if connection.dialect.name == "mssql" else ""
        frame = load_lrfm(connection, as_of, lock_hint)
        labels = score(model, frame)
        now = _now()
        rows = [{"customer": int(c), "segment": (None if int(l) == NEW_CUSTOMER else int(l)), "now": now}
                for c, l in zip(frame.CustomerID, labels)]
        if rows:
            connection.execute(text("UPDATE customers SET segment_id=:segment, segment_scored_at=:now WHERE customer_id=:customer"), rows)
        counts = Counter(int(l) for l in labels)
        return {"customers": len(rows), "new_customers": counts.get(NEW_CUSTOMER, 0),
                "lapsed": counts.get(int(model["lapsed_cluster_id"]), 0), "as_of": str(as_of)}

    def rescore(self) -> dict:
        with self.lock:
            with self.engine.connect().execution_options(isolation_level="SERIALIZABLE") as connection:
                with connection.begin():
                    self._acquire_lock(connection)
                    model = self.active_model(connection)
                    if model is None:
                        raise ClusterError("Chưa có mô hình nền để xếp nhóm khách hàng.")
                    result = self._rescore(connection, model, self.today())
        return {"success": True, **result, "active_version": model["version"],
                "message": f"Đã đặt lại {result['customers']:,} khách lên thước đo của mô hình {model.get('label', '')}.".replace(",", ".")}

    def rescore_if_stale(self) -> bool:
        with self.engine.connect() as connection:
            if self._active_row(connection) is None:
                return False
            latest = connection.execute(text("SELECT MIN(segment_scored_at) FROM customers")).scalar()
        if latest is not None and pd.Timestamp(latest).date() >= self.today():
            return False
        self.rescore()
        return True

    # -- step 7 --------------------------------------------------------------------------------
    def drift(self, record: bool = False) -> dict:
        as_of = self.today()
        with self.engine.connect() as connection:
            model = self.active_model(connection)
            if model is None:
                raise ClusterError("Chưa có mô hình nền để so sánh.")
            current = load_lrfm(connection, as_of)
        report = drift_report(model, current, as_of)
        report["model_label"] = model.get("label")
        report["new_since_reference"] = int((current.HasPurchase & (current.Length < (pd.Timestamp(as_of) - pd.Timestamp(model["reference_date"])).days)).sum())
        if record:
            with self.engine.begin() as connection:
                connection.execute(text(
                    "INSERT INTO drift_checks(model_version, checked_at, as_of_date, sample_size, drift_detected, result_json) "
                    "VALUES(:v, :at, :d, :n, :flag, :json)"),
                    {"v": model["version"], "at": _now(), "d": as_of, "n": report["sample_size"],
                     "flag": report["drift_detected"], "json": json.dumps(report, ensure_ascii=False)})
        return report

    # -- step 2 / 8: training data, options, preview, deploy -----------------------------------
    def training_data(self, connection, source: str) -> tuple[pd.DataFrame, str, date]:
        reference = base_reference_date()
        base = active_population(load_lrfm(connection, reference))
        if source == "base":
            return base, f"Dữ liệu nền OnlineSales: {len(base):,} khách mua trong 12 tháng tới {vn_date(reference)}".replace(",", "."), reference
        if source != "extended":
            raise ClusterError("Nguồn dữ liệu huấn luyện không hợp lệ.")
        today = self.today()
        current = active_population(load_lrfm(connection, today))
        if current.empty:
            raise ClusterError("Chưa có khách nào mua trong 12 tháng gần đây để bổ sung vào dữ liệu nền.")
        combined = pd.concat([base, current], ignore_index=True)
        combined["CustomerID"] = np.arange(len(combined))  # one row per observation (a customer can appear in both periods)
        label = (f"Dữ liệu nền ({len(base):,} khách tại {vn_date(reference)}) + khách hoạt động "
                 f"({len(current):,} khách tại {vn_date(today)})").replace(",", ".")
        return combined, label, today

    def training_options(self, source: str) -> dict:
        with self.engine.connect() as connection:
            data, label, reference = self.training_data(connection, source)
            active = self.active_model(connection)
        fingerprint = hashlib.sha256(data[FEATURES].to_csv(index=False).encode()).hexdigest()
        key = (source, fingerprint)
        if key not in self._options:
            self._options = {key: {**evaluate_k(data), "source": source, "training_source": label,
                                   "reference_date": str(reference), "fingerprint": fingerprint}}
        return {**self._options[key], "active_version": active["version"] if active else None,
                "active_k": active["k"] if active else None}

    def base_analysis(self, k: int) -> dict:
        """Step 2 explored: the base data split into k groups, to compare numbers of groups.

        Nothing is saved or deployed; the counter keeps using the active version. "current_count" tells how many
        customers would be in each group today if this split were applied.
        """
        if not isinstance(k, int) or not 2 <= k <= 10:
            raise ClusterError("Chọn từ 2 đến 10 nhóm.")
        today = self.today()
        with self.engine.connect() as connection:
            data, label, reference = self.training_data(connection, "base")
            current = load_lrfm(connection, today)
        fingerprint = hashlib.sha256(data[FEATURES].to_csv(index=False).encode()).hexdigest()
        with self._cache_lock:
            model = self._analyses.get((k, fingerprint))
        if model is None:
            model = fit_model(data, k, reference, label)
            with self._cache_lock:
                self._analyses = {key: value for key, value in self._analyses.items() if key[1] == fingerprint}
                self._analyses[(k, fingerprint)] = model
        counts = Counter(score(model, current).tolist())
        return {"k": k, "training_source": label, "reference_date": str(reference), "training_rows": model["training_rows"],
                "metrics": model["metrics"], "lapsed_cluster_id": model["lapsed_cluster_id"],
                "clusters": [{**c, "current_count": counts.get(c["cluster_id"], 0)} for c in model["clusters"]],
                "new_customers": counts.get(NEW_CUSTOMER, 0), "all_customers": len(current), "today": str(today)}

    def refresh_names(self) -> dict:
        """Name the ACTIVE version's groups with the current naming rules.

        Only the labels change: caps, mean/std, centres and every customer's group stay exactly as learned.
        Offers whose title is still the default "Ưu đãi — <old name>" follow the new name.
        """
        with self.lock:
            with self.engine.connect().execution_options(isolation_level="SERIALIZABLE") as connection:
                with connection.begin():
                    self._acquire_lock(connection)
                    row = self._active_row(connection)
                    if row is None:
                        return {"renamed": 0, "names": []}
                    model = json.loads(row["model_json"])
                    clusters = renamed_clusters(model)
                    changes = [(old["cluster_id"], old["name"], new["name"])
                               for old, new in zip(model["clusters"], clusters) if old["name"] != new["name"]]
                    if not changes:
                        return {"renamed": 0, "names": [c["name"] for c in clusters]}
                    now = _now()
                    history = model.get("previous_names", [])
                    model["previous_names"] = history + [{"renamed_at": now.isoformat(),
                                                          "names": [c["name"] for c in model["clusters"]]}]
                    model["clusters"] = clusters
                    connection.execute(text("UPDATE model_versions SET model_json=:json WHERE version=:v"),
                                       {"json": json.dumps(model, ensure_ascii=False), "v": model["version"]})
                    for cid, old, new in changes:
                        connection.execute(text("UPDATE customer_segments SET segment_name=:name WHERE id=:id"),
                                           {"name": new, "id": cid})
                        connection.execute(text("UPDATE strategies SET title=:new, updated_at=:now WHERE segment_id=:id AND title=:old"),
                                           {"new": offer_title(new), "old": offer_title(old), "id": cid, "now": now})
                    if not self._is_synchronized(connection, model):
                        raise ClusterError("Đổi tên nhóm chưa đồng bộ; toàn bộ thay đổi đã được hoàn tác.")
            with self._cache_lock:
                self._cached = None
                self._previews, self._options, self._analyses = {}, {}, {}
        return {"renamed": len(changes), "names": [c["name"] for c in clusters],
                "changes": [{"cluster_id": cid, "old": old, "new": new} for cid, old, new in changes]}

    def preview(self, k: int, source: str) -> dict:
        with self.engine.connect() as connection:
            data, label, reference = self.training_data(connection, source)
            active = self.active_model(connection)
        if active is None and source != "base":
            raise ClusterError("Mô hình đầu tiên phải học trên dữ liệu nền.")
        model = fit_model(data, k, reference, label)
        result = {"preview_id": model["version"], "k": k, "source": source, "training_source": label,
                  "reference_date": str(reference), "training_rows": len(data), "metrics": model["metrics"],
                  "clusters": model["clusters"], "expected_version": active["version"] if active else None,
                  "ruler": [{"feature": n, "label": FEATURE_VI[n], "cap": model["caps"].get(n),
                             "mean_log": model["mean"][n], "std_log": model["std"][n],
                             "old_mean_log": active["mean"][n] if active else None,
                             "old_std_log": active["std"][n] if active else None} for n in FEATURES]}
        if active is not None:
            old_labels, new_labels = score(active, data), score(model, data)
            matrix = pd.crosstab(pd.Series(old_labels, name="old"), pd.Series(new_labels, name="new"))
            result["transition"] = {
                "old": [{"cluster_id": c["cluster_id"], "name": c["name"]} for c in active["clusters"]],
                "new": [{"cluster_id": c["cluster_id"], "name": c["name"]} for c in model["clusters"]],
                "counts": [[int(matrix.at[o, n]) if o in matrix.index and n in matrix.columns else 0
                            for n in range(model["k"])] for o in range(active["k"])],
                "unchanged_percent": round(float((old_labels == new_labels).mean() * 100), 1) if active["k"] == k else None,
                "observations": len(data),
            }
            result["old_label"], result["old_metrics"] = active.get("label"), active["metrics"]
        with self._cache_lock:
            self._previews = {model["version"]: {"model": model, "data": data, "expected": result["expected_version"]}}
        return result

    def deploy(self, preview_id: str, expected_version: str | None, reason: str = "", user: str = "") -> dict:
        with self._cache_lock:
            preview = self._previews.get(preview_id)
        if preview is None:
            raise StaleDeployment("Bản xem trước đã hết hạn. Hãy xem trước lại trước khi áp dụng.")
        model, data = preview["model"], preview["data"]
        with self.lock:
            with self.engine.connect().execution_options(isolation_level="SERIALIZABLE") as connection:
                with connection.begin():
                    self._acquire_lock(connection)
                    previous = self.active_model(connection)
                    current_version = previous["version"] if previous else None
                    if current_version != expected_version or current_version != preview["expected"]:
                        raise StaleDeployment("Mô hình đang áp dụng đã thay đổi ở phiên khác. Hãy xem trước lại.")
                    now = _now()
                    preserved, archived = set(), 0
                    if previous is not None:
                        old_labels, new_labels = score(previous, data), score(model, data)
                        for policy in connection.execute(text("SELECT * FROM strategies ORDER BY id")).mappings().all():
                            cid = policy["segment_id"]
                            keep = False
                            if cid is not None and 0 <= cid < model["k"] and previous["k"] == model["k"]:
                                before, after = set(np.flatnonzero(old_labels == cid)), set(np.flatnonzero(new_labels == cid))
                                keep = bool(before | after) and len(before & after) / len(before | after) >= MIN_OVERLAP
                            if keep:
                                preserved.add(int(cid))
                                continue
                            exists = connection.execute(text("SELECT 1 FROM strategy_history WHERE original_strategy_id=:id"),
                                                        {"id": policy["id"]}).first()
                            if not exists:
                                connection.execute(text(
                                    "INSERT INTO strategy_history(original_strategy_id, segment_id, snapshot_json, archived_at, deployment_version) "
                                    "VALUES(:id, :segment, :snapshot, :at, :version)"),
                                    {"id": policy["id"], "segment": cid, "at": now, "version": model["version"],
                                     "snapshot": json.dumps(dict(policy), ensure_ascii=False, default=str)})
                            connection.execute(text("DELETE FROM strategies WHERE id=:id"), {"id": policy["id"]})
                            archived += 1
                        connection.execute(text("UPDATE model_versions SET status='ARCHIVED', archived_at=:at WHERE status='ACTIVE'"), {"at": now})
                    else:
                        connection.execute(text("DELETE FROM strategies"))
                    number = connection.execute(text("SELECT COUNT(*) FROM model_versions")).scalar_one() + 1
                    connection.execute(text(
                        "INSERT INTO model_versions(version, label, status, k, reference_date, training_source, training_rows, "
                        "model_json, reason, created_by, created_at, activated_at) VALUES(:version, :label, 'ACTIVE', :k, :ref, "
                        ":source, :rows, :json, :reason, :user, :created, :at)"),
                        {"version": model["version"], "label": f"v{number}", "k": model["k"], "ref": date.fromisoformat(model["reference_date"]),
                         "source": model["training_source"][:300], "rows": model["training_rows"],
                         "json": json.dumps(model, ensure_ascii=False), "reason": (reason or "")[:500] or None,
                         "user": (user or "")[:100] or None, "created": datetime.fromisoformat(model["created_at"]), "at": now})
                    connection.execute(text("UPDATE customers SET segment_id=NULL"))
                    connection.execute(text("DELETE FROM customer_segments"))
                    for cluster in model["clusters"]:
                        connection.execute(text("INSERT INTO customer_segments(id, segment_name, color_code) VALUES(:id, :name, :color)"),
                                           {"id": cluster["cluster_id"], "name": cluster["name"], "color": cluster["color"]})
                        if cluster["cluster_id"] not in preserved:
                            connection.execute(text(
                                "INSERT INTO strategies(segment_id, title, discount_percent, gift_description, cashier_script, is_active, updated_at) "
                                "VALUES(:id, :title, 0, '', :script, 0, :now)"),
                                {"id": cluster["cluster_id"], "title": offer_title(cluster["name"]), "now": now,
                                 "script": "Xin chào anh/chị. Em có thể hỗ trợ mình tìm sản phẩm phù hợp không ạ?"})
                    model_with_label = {**model, "label": f"v{number}"}
                    rescored = self._rescore(connection, model_with_label, self.today())
                    if not self._is_synchronized(connection, model):
                        raise ClusterError("Kiểm tra đồng bộ thất bại; toàn bộ thay đổi đã được hoàn tác.")
            with self._cache_lock:
                self._cached = model_with_label
                self._previews = {}
                self._options = {}
        return {"success": True, "active_version": model["version"], "label": f"v{number}", "active_k": model["k"],
                "clusters": model["clusters"], "preserved_strategies": len(preserved),
                "draft_strategies": model["k"] - len(preserved), "archived_strategies": archived, "rescored": rescored,
                "message": f"Đã áp dụng mô hình v{number} ({model['k']} nhóm) và xếp lại {rescored['customers']:,} khách.".replace(",", ".")}
