# Cosmetic CRM AI — phân cụm khách hàng LRFM với mô hình nền và đề xuất chiến lược

Hệ thống bán hàng mỹ phẩm (POS) tích hợp AI: phân cụm khách hàng **LRFM** bằng K-means theo cách
**mô hình chỉ học ở bước huấn luyện** — khách mới chỉ được "đặt lên thước đo cũ", nên thêm bao nhiêu khách
cũng không làm lệch nhóm; khi khách thật khác hẳn dữ liệu nền, hệ thống **phát hiện lệch** và cho phép
**huấn luyện lại có kiểm soát**. Trợ lý Gemini đề xuất ưu đãi, quầy POS áp dụng ưu đãi đã duyệt, có phân quyền
và ca làm việc. Gồm Spring Boot (giao diện, nghiệp vụ, SQL Server) và FastAPI (phân cụm, trợ lý AI).

## Dữ liệu nền: OnlineSales

Tệp `data/raw/OnlineSales.xlsx` (250.000 dòng, 03/01/2020 → 11/01/2021). Cột: TransactionNo, CustomerID, Quantity,
UnitPrice (USD), Revenue, TransactionDate, Product Description.

> **Cần bổ sung nguồn gốc** (trang tải, tác giả, ngày tải, giấy phép) trong báo cáo đồ án.

`python data/prepare_dataset.py` làm sạch và ghi các bước, số dòng vào `data/processed/data_report.md`:

| Bước | Quy tắc |
|---|---|
| Làm sạch | bỏ 157 dòng thiếu mô tả sản phẩm, 42.273 dòng số lượng ≤ 0 → còn 207.599 dòng |
| Thời gian | **giữ nguyên ngày gốc** (không dời mốc) |
| Tiền | 25.600 đ/USD — tỷ giá trung tâm NHNN 10/11/2026 |
| Sản phẩm | mỗi mô tả là một sản phẩm (12.066); nhóm hàng tiếng Việt theo từ khóa trong [data/category_rules.csv](data/category_rules.csv) |
| Bày bán tại POS | 200 sản phẩm có nhiều hóa đơn nhất 364 ngày cuối, tên tiếng Việt dịch tay ([data/product_names_vi.csv](data/product_names_vi.csv)), tồn 100 |
| Khách hàng | giữ CustomerID gốc, mã `KH<id>`; **họ tên và số điện thoại là GIẢ LẬP** (số `0100` + 6 số cuối mã) để tra cứu tại quầy, không dùng trong phân tích; không lưu giới tính, địa chỉ |
| Hóa đơn | giữ TransactionNo gốc, phương thức “Trực tuyến” (ONLINE) |

Kết quả: 13.637 hóa đơn · 3.263 khách · 12.066 sản phẩm. Ảnh sản phẩm là hình minh họa theo nhóm hàng lưu trong dự án.

## Phân cụm LRFM với mô hình nền

| Chỉ số | Cách tính tại ngày đo D |
|---|---|
| **L** – gắn bó | D − ngày mua đầu tiên |
| **R** – lần mua gần nhất | D − ngày mua cuối |
| **F** – tần suất | số hóa đơn trong 365 ngày tới D |
| **M** – chi tiêu | tiền chi trong 365 ngày tới D |

1. **Dữ liệu nền** cố định tại **ngày tham chiếu 12/01/2021** (ngày sau hóa đơn cuối).
2. **Huấn luyện — bước duy nhất mô hình học**: 3.205 khách mua trong 12 tháng tới ngày tham chiếu. L, F, M chặn ở
   phân vị 99%, lấy log(1+x), chuẩn hóa bằng mean/std của chính các khách này; K-means (k-means++, random_state 42).
   Ngưỡng cắt, mean, std, tâm nhóm, tên nhóm và chỉ số đánh giá được lưu công khai trong bảng `model_versions`.
3. **Đo khách** (cũ hoặc mới) theo đúng định nghĩa trên tại ngày hôm nay.
4. **Đặt lên thước đo cũ**: z = (log(1 + min(x, ngưỡng)) − mean) / std → tâm nhóm gần nhất. Không học lại.
   Chưa mua lần nào → “Chưa mua hàng” (ngoài các nhóm). Không mua 365 ngày → nhóm có lần mua gần nhất xa nhất của mô hình
   (với v1 là “Khách hàng ngủ đông”).
5. **Tại quầy**: Spring tính LRFM của khách trong SQL rồi hỏi nhóm; sau khi thanh toán, nhóm được tính lại với đúng hóa đơn vừa bán.
6. **Xếp lại hằng ngày**: R và L tăng mỗi ngày nên mọi khách được đặt lại lên thước đo một lần/ngày (hoặc bấm nút).
7. **Kiểm tra lệch**: so khách mua 12 tháng gần đây với dữ liệu nền — lệch trung bình > 0,5 độ lệch chuẩn,
   > 20% khách ngoài khoảng đã học, hoặc một nhóm chiếm > 60%. Chỉ kết luận khi có ít nhất 30 khách; có nhật ký `drift_checks`.
8. **Huấn luyện lại có kiểm soát**: xem trước phiên bản mới (dữ liệu nền + khách hoạt động hôm nay), so chất lượng,
   thước đo cũ/mới và **ma trận chuyển nhóm**, ghi lý do rồi mới áp dụng. Phiên bản cũ chuyển sang lưu trữ, không xóa.
   Ưu đãi của nhóm còn ≥ 60% thành viên (Jaccard) được giữ, nhóm khác nhận ưu đãi nháp đang tắt.

### Chọn số nhóm (K)

Số nhóm **không cố định**: máy thử K = 2…10 trên 3.205 khách dữ liệu nền và chấm điểm từng cách chia.

| K | Silhouette (cao tốt) | Davies–Bouldin (thấp tốt) | Nhóm nhỏ nhất |
|---|---|---|---|
| 2 | 0,354 | 1,094 | 39,5% |
| **3** | **0,365** | **0,969** | 23,1% |
| 4 | 0,328 | 1,018 | 15,8% |
| 5 | 0,306 | 1,134 | 12,4% |
| 6–10 | 0,27–0,30 | 1,08–1,14 | 4,6–8,1% |

Gợi ý số nhóm: K ≥ 3, mọi nhóm ≥ 5% khách, Silhouette cao nhất (chênh dưới 0,01 thì chọn K lớn hơn) → **K = 3**.
Trang **Tổng quan** có ô “Chia dữ liệu nền thành bao nhiêu nhóm?”: bấm 2…10 (hoặc bấm một điểm trên biểu đồ) để xem
cơ cấu và chân dung khách của cách chia đó. Đây chỉ là **xem thử**, không ghi gì vào SQL; muốn dùng thật thì bấm
**Áp dụng K nhóm…** → trang Mô hình phân cụm → **Xem trước** → **Áp dụng** (tạo phiên bản mới, phiên bản cũ được lưu trữ).

### Tên nhóm

Tên đặt tự động từ khách điển hình (trung vị) của nhóm, theo thuật ngữ phân khúc RFM quen dùng trong marketing:

| Tên nhóm | Đặc điểm khách điển hình |
|---|---|
| Khách hàng VIP | chi tiêu cao nhất, mua thường xuyên, vẫn đang mua (nhóm ≤ 25% khách) |
| Khách hàng trung thành | mua gần đây và mua thường xuyên |
| Khách hàng tiềm năng | mua gần đây, chi tiêu cao, chưa mua thường xuyên |
| Khách hàng triển vọng | mua gần đây, chi tiêu thấp, chưa mua thường xuyên |
| Khách hàng mới | thời gian gắn bó (L) ngắn, chưa mua nhiều |
| Khách hàng cần quan tâm | mua thường xuyên nhưng lần mua gần nhất đã lâu vừa phải |
| Khách hàng sắp ngủ đông | ít đơn, lần mua gần nhất đã lâu vừa phải |
| Khách hàng có nguy cơ rời bỏ | từng mua thường xuyên nhưng đã lâu không quay lại |
| Khách hàng ngủ đông | ít đơn và đã lâu không quay lại |

“Gần đây / lâu” và “thường xuyên” được so với chính dữ liệu huấn luyện (phân vị 1/3, 2/3 của R; phân vị 75% của F).
Hai nhóm trùng tên thì thêm mức chi tiêu (“· chi tiêu cao”…). Khách chưa có hóa đơn hiển thị “Chưa mua hàng”.

Mô hình v1 (K=3): **Khách hàng ngủ đông** 40,7% · **Khách hàng mới** 23,1% · **Khách hàng trung thành** 36,3%.
(Ngày 12/11/2026 tên nhóm của v1 được đổi từ Lâu chưa ghé / Mới quen / Mua đều; chỉ đổi tên, thước đo và nhóm của từng
khách giữ nguyên. Tên cũ lưu trong `model_json.previous_names`. FastAPI tự áp quy tắc đặt tên mới cho phiên bản đang
dùng khi khởi động; ưu đãi còn tên mặc định “Ưu đãi — …” đổi theo, ưu đãi đã tự đặt tên giữ nguyên.)

Lưu ý khi đọc: dữ liệu nền kết thúc năm 2021, nên **hôm nay** mọi khách nền đều thuộc nhóm “Khách hàng ngủ đông”.
Đây là đúng thực tế (họ không mua hơn 365 ngày), không phải lỗi. Khách mua mới tại quầy được xếp theo hành vi thật.

## Cấu trúc mã Spring Boot (`frontend-pos/src/main/java/com/example/PhanTichAI`)

| Thư mục | Nội dung |
|---|---|
| `entity/` | 15 lớp ánh xạ 15 bảng SQL (JPA): Customer, Order, OrderItem, Product, Strategy, Category, Role, User, Shift, CustomerSegment, ModelVersion, DriftCheck, StrategyHistory, MemberAccount, LoyaltyPoint |
| `repository/` | truy vấn JPA cho các bảng bán hàng dùng nhiều (khách, sản phẩm, hóa đơn, dòng hàng, ưu đãi) |
| `service/`, `controller/` | nghiệp vụ và API; truy vấn thống kê viết SQL trực tiếp bằng `JdbcTemplate` |
| `security/`, `dto/` | đăng nhập, phân quyền; dữ liệu trao đổi với trình duyệt và dịch vụ AI |

`CustomerSegment`, `ModelVersion`, `DriftCheck`, `StrategyHistory` đánh dấu `@Immutable`: dịch vụ AI (Python) ghi các bảng
này khi huấn luyện, kiểm tra lệch và áp dụng mô hình; phía Java chỉ đọc. Test `EntityMappingTest` kiểm tra mỗi bảng trong
`database/schema.sql` có đúng một entity với đúng các cột.

**Database đang dùng từ trước (chưa có bảng thẻ thành viên):** chạy `python database\migrate.py`. Script chỉ thêm bảng
`member_accounts`, `loyalty_points` và cộng điểm cho hóa đơn đã bán tại quầy; chạy lại nhiều lần vẫn an toàn, không xóa dữ liệu.

## Cài đặt từ đầu

Cần Java 17+, Maven, Python 3.11, SQL Server và ODBC Driver 17 for SQL Server.

```powershell
cd D:\CODING\cosmetic-crm-ai
python -m pip install -r backend-pos\requirements.txt
python data\prepare_dataset.py        # OnlineSales.xlsx -> data\processed + data_report.md
python database\build_database.py     # tạo database cosmetic_crm_db và nạp dữ liệu (~4 phút)
```

Cấu hình kết nối trong `backend-pos/.env` (`DB_SERVER`, `DB_NAME=cosmetic_crm_db`, `DB_USER`, `DB_PASSWORD`, khóa Gemini,
tùy chọn `BASE_REFERENCE_DATE`) — xem [backend-pos/.env.example](backend-pos/.env.example). Spring Boot đọc
`frontend-pos/src/main/resources/application.properties` và lấy `DB_USER`/`DB_PASSWORD` từ chính file `backend-pos/.env`
này (file `.env` không đưa lên GitHub, nên máy mới phải tự tạo từ `.env.example`). Script từ chối đụng tới database của các project cũ
(`cosmetic_pos_db`, `cosmetic_pos_ai`). Không chạy `--recreate` trên database đang dùng vì sẽ xóa hóa đơn bán tại quầy.

## Chạy ứng dụng

```powershell
cd D:\CODING\cosmetic-crm-ai\frontend-pos
mvn spring-boot:run
```

```powershell
cd D:\CODING\cosmetic-crm-ai\backend-pos
python -X utf8 -m uvicorn main:app --port 8000
```

Mở http://localhost:8080/login. Lần đầu: **Mô hình phân cụm** → chọn số nhóm → **Xem trước** → **Áp dụng** để tạo mô hình nền v1,
rồi vào **Chiến lược ưu đãi** điền và bật ưu đãi cho từng nhóm.

### Tài khoản demo (mật khẩu `123456`)

| Tài khoản | Vai trò | Dùng để |
|---|---|---|
| `admin` | Quản trị viên | Toàn quyền, quản lý tài khoản |
| `manager` | Quản lý | Mô hình phân cụm, ưu đãi, báo cáo, ca làm việc |
| `nhanvien1`, `nhanvien2`, `nhanvien3` | Thu ngân | Đứng quầy POS theo ca |

Tài khoản demo được tạo khi Spring Boot khởi động nếu chưa có (`app.demo-accounts.enabled=true`). Đặt `false` và đổi mật khẩu trước khi dùng thật.

## Trợ lý AI

Gemini nhận số liệu tổng hợp (LRFM từng nhóm, phiên bản mô hình, số khách hiện tại theo nhóm, kết quả kiểm tra lệch,
sản phẩm đang bán, hàng bán chạy, ưu đãi đang lưu), không nhận tên/số điện thoại khách. Đề xuất được kiểm tra: đúng mã nhóm,
quà là sản phẩm đang bán còn kho, không nói về lợi nhuận khi chưa có giá vốn. Trợ lý không tự áp dụng mô hình hay ưu đãi.

## Quầy POS và ca làm việc

- Thu ngân **mở ca** trước khi bán; mọi hóa đơn ghi vào ca và nhân viên đang đứng quầy.
- Tra cứu khách bằng số điện thoại (VD `0100024694`) hoặc mã khách (`KH24694`); POS hiển thị nhóm, R, số đơn và chi tiêu 12 tháng, ưu đãi đã duyệt và quà còn kho.
- **Quét thẻ thành viên bằng camera:** bấm 📷 cạnh nút Tìm, đưa mã QR trên điện thoại của khách vào khung; POS tự tra cứu
  như khi gõ mã. Dùng webcam laptop; trình duyệt chỉ cho mở camera khi vào POS bằng `http://localhost:8080` (hoặc HTTPS).
  Ảnh camera chỉ được đọc trong trình duyệt, không lưu, không gửi đi. Đọc mã QR bằng thư viện jsQR 1.4.0 (Apache-2.0) lưu
  sẵn trong `static/js/vendor/` nên không cần mạng. Máy quét mã USB loại 2D cũng dùng được với ô tra cứu.
- Tiền giảm tại quầy **chỉ** đến từ ưu đãi đã duyệt của nhóm khách; không có ô giảm tay. Máy chủ tự tính lại tổng tiền và từ chối hóa đơn có số tiền khác.
- Thanh toán **Tiền mặt, Chuyển khoản hoặc Thẻ**. **Kết ca** có báo cáo, in và xuất CSV doanh thu/quà tặng.

## Thẻ thành viên & tích điểm (trang cho khách)

Khách mở **`http://<địa chỉ máy tính>:8080/member`** trên điện thoại. Ở quầy, nút **📱 Thẻ thành viên** hiện sẵn mã QR của địa chỉ
này (máy tự tìm địa chỉ Wi-Fi, VD `http://192.168.100.215:8080/member`) để khách quét bằng camera điện thoại. Điện thoại và máy
tính phải dùng chung một Wi-Fi. Có thể đặt cố định bằng `member.public-url` trong `application.properties`.

| Việc | Cách làm |
|---|---|
| Khách mới | **Đăng ký**: họ tên, số điện thoại, PIN 6 số → có thẻ ngay (mã KH… mới, chưa thuộc nhóm nào cho tới hóa đơn đầu) |
| Khách đã mua ở cửa hàng / quên PIN | Thu ngân tra cứu khách → **🔑 Cấp mã kích hoạt** (6 số, 10 phút, dùng một lần) → khách chọn **Kích hoạt thẻ**, nhập SĐT + mã + PIN mới. Không ai nhận được thẻ của người khác chỉ bằng số điện thoại. |
| Lần sau | **Đăng nhập** bằng SĐT + PIN. Sai 5 lần thì khóa 15 phút. PIN và mã kích hoạt chỉ lưu dạng băm BCrypt. |
| Tại quầy | Khách đưa thẻ, thu ngân bấm 📷 quét → POS hiện khách, nhóm, ưu đãi, **điểm** và trạng thái thẻ |

- **Mã QR đổi mỗi 60 giây** (`CM1.<mã khách>.<phút>.<chữ ký HMAC-SHA256>`, khóa bí mật trong `.runtime/member-qr.key`):
  ảnh chụp mã cũ hết dùng được sau tối đa 2 phút, không ai tự tạo được mã của khách khác. Thu ngân vẫn tra được bằng SĐT/mã khách.
- **Tích điểm:** 1 điểm cho mỗi 10.000 đ khách thực trả (sau ưu đãi), làm tròn xuống, ghi trong cùng giao dịch với hóa đơn
  (bảng `loyalty_points`). Hóa đơn in ghi số điểm cộng và tổng điểm. Hóa đơn lịch sử OnlineSales không tính điểm. Chưa có đổi điểm.
- Trang khách hiện: thẻ, mã QR, điểm, **ưu đãi đang có** (không hiện tên nhóm nội bộ như "ngủ đông"), 10 hóa đơn và 10 lần cộng điểm gần nhất.
  Có thể "Thêm vào màn hình chính" để mở như ứng dụng.
- Phiên của khách tách hẳn với tài khoản nhân viên: khách không vào được POS hay trang quản trị (test `MemberSecurityTest`).

## Kiểm thử

```powershell
cd D:\CODING\cosmetic-crm-ai\backend-pos
python -X utf8 -m unittest discover -s tests -v
cd ..\frontend-pos
mvn test
```

Test quan trọng: `test_new_customers_never_move_the_ruler_or_the_base_groups` — thêm 400 khách chi tiêu rất lớn,
mean/std/tâm nhóm và nhóm của 180 khách nền không đổi.

## Cấu trúc

```text
data/          OnlineSales.xlsx (raw), quy tắc nhóm hàng, tên tiếng Việt, prepare_dataset.py, processed/ + data_report.md
database/      schema.sql (model_versions, drift_checks, …) và build_database.py (tạo cosmetic_crm_db)
backend-pos/   FastAPI: clustering_service.py (mô hình nền LRFM), assistant_service.py, main.py, tests/
frontend-pos/  Spring Boot: POS, quản trị, tab Mô hình phân cụm (model.js), ca làm việc, doanh thu
```

Project trước (Open e-commerce 1.0) giữ nguyên tại `D:\CODING\cosmetic-pos-ai` với database `cosmetic_pos_ai`.
