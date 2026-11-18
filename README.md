# Cosmetic CRM AI — phân cụm khách hàng LRFM với mô hình nền và đề xuất chiến lược

Hệ thống bán hàng mỹ phẩm (POS) tích hợp AI: phân cụm khách hàng **LRFM** bằng K-means theo cách
**mô hình chỉ học ở bước huấn luyện** — khách mới chỉ được "đặt lên thước đo cũ", nên thêm bao nhiêu khách
cũng không làm lệch nhóm; khi khách thật khác hẳn dữ liệu nền, hệ thống **phát hiện lệch** và cho phép
**huấn luyện lại có kiểm soát**. Trợ lý Gemini đề xuất ưu đãi, quầy POS áp dụng ưu đãi đã duyệt, có phân quyền
và ca làm việc. Gồm Spring Boot (giao diện, nghiệp vụ, SQL Server) và FastAPI (phân cụm, trợ lý AI).

## Dữ liệu nền: OnlineSales

Tệp `data/raw/OnlineSales.xlsx` (250.000 dòng, 03/01/2020 → 11/01/2021). Cột: TransactionNo, CustomerID, Quantity,
UnitPrice (USD), Revenue, TransactionDate, Product Description.

**Nguồn:** bộ dữ liệu thực hành đi kèm sách *Essentials of Marketing Analytics* — Joseph F. Hair Jr., Dana E. Harrison,
Haya Ajjan, McGraw Hill (ấn bản 2021, ISBN 9781260597745; có bản phát hành 2024). Khi viết báo cáo, ghi rõ ấn bản và chương/trang
có bộ dữ liệu. Sách thuộc bản quyền của nhà xuất bản: kho này để riêng tư; trước khi công khai, kiểm tra điều khoản dùng lại dữ liệu.

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
| `entity/` | 25 lớp ánh xạ 25 bảng SQL (JPA): Customer, Order, OrderItem, Product, Strategy, Category, Role, User, Shift, CustomerSegment, ModelVersion, DriftCheck, StrategyHistory, MemberAccount, LoyaltyPoint, AssociationRun, ProductAssociation, StockReceipt, StockLot, OrderItemLot, CampaignExperiment, CampaignAssignment, ProductReview, ShiftCashMovement, OrderPaymentChange |
| `repository/` | truy vấn JPA cho các bảng bán hàng dùng nhiều (khách, sản phẩm, hóa đơn, dòng hàng, ưu đãi) |
| `service/`, `controller/` | nghiệp vụ và API; truy vấn thống kê viết SQL trực tiếp bằng `JdbcTemplate` |
| `security/`, `dto/` | đăng nhập, phân quyền; dữ liệu trao đổi với trình duyệt và dịch vụ AI |

`CustomerSegment`, `ModelVersion`, `DriftCheck`, `StrategyHistory` đánh dấu `@Immutable`: dịch vụ AI (Python) ghi các bảng
này khi huấn luyện, kiểm tra lệch và áp dụng mô hình; phía Java chỉ đọc. Test `EntityMappingTest` kiểm tra mỗi bảng trong
`database/schema.sql` và `database/migrations/` có đúng một entity với đúng các cột.

**Cập nhật database đang dùng:** sau khi tải code mới, chạy `python database\migrate.py`. Script áp dụng lần lượt
`database/migrations/*.sql` (thẻ thành viên & điểm, gợi ý mua kèm, …); mỗi file chỉ thêm bảng/cột mới, chạy lại nhiều lần vẫn
an toàn, không xóa dữ liệu. Database tạo mới bằng `build_database.py` tự áp dụng đủ các migration.

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
- **Đối soát tiền mặt cuối ca** (migration `008_shift_cash_review.sql`): đếm két theo mệnh giá; tiền phải có = đầu ca + bán tiền mặt +
  phiếu thu − phiếu chi trong ca; lệch quá **±10.000 đ** thì thu ngân ghi lý do và ca chờ **quản lý hoặc quản trị viên** xác nhận (sửa
  phương thức thanh toán bấm nhầm có lưu lịch sử; thiếu → nhân viên bồi hoàn / cửa hàng chịu; dư → nộp quỹ). Chi tiết trong
  [ADMIN_GUIDE.md](ADMIN_GUIDE.md#ca-làm-việc-tại-pos).

## Thẻ thành viên & tích điểm (trang cho khách)

Khách mở trang **`/member`** trên điện thoại. Ở quầy, nút **📱 Thẻ thành viên** hiện sẵn mã QR của địa chỉ phù hợp nhất để khách
quét bằng camera điện thoại:

| Cách | Khách dùng mạng nào | Làm gì |
|---|---|---|
| **Qua Internet** (đường hầm Cloudflare, miễn phí) | 4G, Wi-Fi bất kỳ | Bấm đúp [scripts/public-member.cmd](scripts/public-member.cmd) (cần cài một lần: `winget install --id Cloudflare.cloudflared -e`), giữ cửa sổ mở. Địa chỉ dạng `https://….trycloudflare.com/member`, **đổi mỗi lần bật** |
| Chung Wi-Fi | cùng Wi-Fi với máy tính | Không cần làm gì; máy tự tìm địa chỉ Wi-Fi, VD `http://192.168.1.42:8080/member` |
| Địa chỉ cố định | 4G, Wi-Fi bất kỳ | Có tên miền (VD `.id.vn`) + Cloudflare Tunnel có tên → đặt `member.public-url` trong `application.properties` |

**An toàn khi mở ra Internet** ([PublicMemberGateway](frontend-pos/src/main/java/com/example/PhanTichAI/security/PublicMemberGateway.java)):
đường hầm chỉ nối vào **cổng 8081**, cổng này chỉ nhận kết nối từ chính máy tính (127.0.0.1) và **chỉ mở trang thẻ khách**
(`/member`, `/api/member/**` và 5 file giao diện của trang). POS, trang quản trị, đăng nhập nhân viên đều trả "không tìm thấy" (404),
nên tài khoản demo không lộ ra ngoài. Mỗi thiết bị chỉ được đăng ký 5 lần/giờ, đăng nhập 20 lần/10 phút, kích hoạt 10 lần/10 phút
([MemberRateLimiter](frontend-pos/src/main/java/com/example/PhanTichAI/security/MemberRateLimiter.java)). Giới hạn của đường hầm miễn phí:
tối đa 200 truy cập cùng lúc, chỉ dùng để thử nghiệm/demo; laptop tắt thì khách không mở được thẻ.

| Việc | Cách làm |
|---|---|
| Khách mới | **Đăng ký**: họ tên, số điện thoại, PIN 6 số → có thẻ ngay (mã KH… mới, chưa thuộc nhóm nào cho tới hóa đơn đầu) |
| Khách đã mua ở cửa hàng / quên PIN | Thu ngân tra cứu khách → **🔑 Cấp mã kích hoạt** (6 số, 10 phút, dùng một lần) → khách chọn **Kích hoạt thẻ**, nhập SĐT + mã + PIN mới. Không ai nhận được thẻ của người khác chỉ bằng số điện thoại. |
| Lần sau | **Đăng nhập** bằng SĐT + PIN. Sai 5 lần thì khóa 15 phút. PIN và mã kích hoạt chỉ lưu dạng băm BCrypt. |
| Tại quầy | Khách đưa thẻ, thu ngân bấm 📷 quét → POS hiện khách, nhóm, ưu đãi, **điểm** và trạng thái thẻ |

- **Mã QR đổi mỗi 60 giây** (`CM1.<mã khách>.<phút>.<chữ ký HMAC-SHA256>`, khóa bí mật trong `.runtime/member-qr.key`):
  ảnh chụp mã cũ hết dùng được sau tối đa 2 phút, không ai tự tạo được mã của khách khác. Thu ngân vẫn tra được bằng SĐT/mã khách.
- **Tích điểm:** 1 điểm cho mỗi 10.000 đ khách thực trả (sau ưu đãi và sau khi trừ điểm), làm tròn xuống, × hệ số hạng thẻ;
  ghi trong cùng giao dịch với hóa đơn (bảng `loyalty_points`, loại `EARN`). Hóa đơn lịch sử OnlineSales không tính điểm.
- **Hạng thẻ** (luật rõ ràng khách nhìn thấy — khác nhóm LRFM nội bộ của AI), theo chi tiêu **tại cửa hàng** 12 tháng gần nhất:

  | Hạng | Chi tiêu 12 tháng | Điểm |
  |---|---|---|
  | Thành viên | dưới 2.000.000 đ | ×1 |
  | Bạc | từ 2.000.000 đ | ×1,5 |
  | Vàng | từ 5.000.000 đ | ×2 |

  Không dùng dữ liệu nền để xếp hạng vì đó là số liệu mua sỉ (hóa đơn trung vị 13,1 triệu đ — dùng thì ai cũng hạng Vàng).
- **Đổi điểm tại quầy:** **100 điểm = giảm 10.000 đ** (hoàn 1%), theo từng 100 điểm, tối đa **50% hóa đơn sau ưu đãi**.
  Chỉ dùng được sau khi thu ngân **bấm 📷 quét mã QR trên điện thoại của khách** (chứng minh khách có mặt, hiệu lực 15 phút, một lần
  quét cho một hóa đơn) — gõ số điện thoại không đủ để tiêu điểm của người khác. Máy chủ khóa thẻ của khách trong giao dịch
  (`UPDLOCK`) để không tiêu trùng điểm. Hóa đơn ghi `orders.points_redeemed`, `points_discount` (đã nằm trong `discount_amount`);
  sổ điểm ghi một dòng âm loại `REDEEM`.
- Trang khách hiện: thẻ (màu theo hạng), mã QR, điểm và số tiền quy đổi, thanh tiến độ lên hạng, **ưu đãi đang có** (không hiện tên
  nhóm nội bộ như "ngủ đông"), 10 hóa đơn và 10 lần cộng/đổi điểm gần nhất.
  Có thể "Thêm vào màn hình chính" để mở như ứng dụng.
- Phiên của khách tách hẳn với tài khoản nhân viên: khách không vào được POS hay trang quản trị (test `MemberSecurityTest`).

## Gợi ý mua kèm (phân tích giỏ hàng)

Theo chương 9 (Market Basket Analysis) của sách *Essentials of Marketing Analytics*: đếm trên mọi hóa đơn (dữ liệu nền
OnlineSales + hóa đơn bán tại quầy; bỏ dòng quà tặng giá 0) xem hai sản phẩm đang bày bán hay được mua chung đến mức nào.

| Chỉ số | Công thức | Ý nghĩa |
|---|---|---|
| Mua chung | số hóa đơn có cả A và B | chỉ giữ cặp **≥ 30 hóa đơn** |
| Support | có cả A và B / tất cả hóa đơn | cặp này phổ biến tới đâu |
| Confidence A → B | có cả A và B / có A | "65% khách mua A cũng mua B" — câu thu ngân thấy |
| Lift | confidence / tỉ lệ hóa đơn có B | > 1: mua chung nhiều hơn ngẫu nhiên; chỉ giữ lift > 1 |

Kết quả trên dữ liệu hiện tại: 13.642 hóa đơn → **110 cặp, 220 quy tắc**. VD *Dầu xả Clear Frizz Control → Dầu gội Clear Frizz
Control*: 60 hóa đơn mua chung, confidence 65%, lift 89,9.

- **Tính:** FastAPI ([basket_service.py](backend-pos/basket_service.py)) tự tính lần đầu khi khởi động; trang quản trị → tab
  **Gợi ý mua kèm** → **Tính lại** để gộp hóa đơn mới. Bảng `association_runs` (lịch sử lần tính), `product_associations` (quy tắc).
- **POS:** khi thêm hàng vào giỏ, khung **🛍️ Khách hay mua kèm** hiện tối đa 3 sản phẩm còn hàng có confidence cao nhất, kèm lý do;
  bấm **+ Thêm** để đưa vào hóa đơn. Dòng hóa đơn thêm từ gợi ý được đánh dấu `order_items.from_suggestion = 1`.
- **Đo hiệu quả:** tab Gợi ý mua kèm cho biết bao nhiêu hóa đơn tại quầy có món từ gợi ý, số lượng và doanh thu từ gợi ý.

## Đo hiệu quả ưu đãi (nhóm đối chứng)

Câu hỏi: **ưu đãi có thật sự làm khách quay lại không?** ([ExperimentService](frontend-pos/src/main/java/com/example/PhanTichAI/service/ExperimentService.java),
migration `005_campaign_experiments.sql`).

- Khi quản lý **bật** một chiến lược, mọi khách đang thuộc nhóm đó được chia ngẫu nhiên — cố định theo mã khách (SHA-256 của
  "lần đo:mã khách") — thành **90% nhận ưu đãi** và **10% nhóm đối chứng**. Khách nhóm đối chứng không được giảm giá/tặng quà tại quầy
  (thu ngân thấy ghi chú) và trang thẻ của họ không hiện ưu đãi. **Tắt** chiến lược (hoặc áp dụng phân cụm khác) là kết thúc lần đo.
- Trang quản trị → tab **Hiệu quả ưu đãi**: trong **30 ngày** so tỉ lệ quay lại mua tại quầy và chi tiêu trung bình của hai phía;
  kiểm định hai tỉ lệ (z-test), khoảng tin cậy 95% của chênh lệch, kết luận bằng lời: *có hiệu quả / chưa thấy khác biệt / làm giảm /
  chưa đủ dữ liệu* (cần ≥ 30 khách nhóm đối chứng và ≥ 10 lượt quay lại). Trang cho biết cần bao nhiêu khách để phát hiện chênh lệch
  5 điểm % (VD từ 10% lên 15%: khoảng 686 khách mỗi phía).
- Hiện cửa hàng mới có vài khách mua tại quầy nên kết quả thật sẽ là "chưa đủ dữ liệu". Khung **Mô phỏng** (ghi rõ *MÔ PHỎNG — không
  phải dữ liệu thật*, không lưu gì) cho xem một lần đo trông thế nào với số khách và tỉ lệ bạn giả định.

## Kho theo lô & hạn sử dụng

Mỹ phẩm có hạn dùng, nên tồn kho được quản lý theo **lô** ([InventoryService](frontend-pos/src/main/java/com/example/PhanTichAI/service/InventoryService.java),
migration `004_inventory_lots.sql`, bảng `stock_receipts`, `stock_lots`, `order_item_lots`):

- `products.stock_quantity` luôn bằng tổng số còn lại của các lô (test `everyProductStockEqualsItsLots`).
- **Bán được** = các lô chưa hết hạn. POS chỉ hiện và chỉ cho bán số này; khi thanh toán, máy lấy **lô có hạn gần nhất trước**
  (FEFO), khóa lô trong giao dịch, và ghi mỗi dòng hóa đơn lấy từ lô nào (truy vết khi cần thu hồi một lô lỗi).
- Trang quản trị → tab **Kho & hạn dùng**: cảnh báo lô còn **≤ 60 ngày** hoặc đã hết hạn, sản phẩm còn **≤ 10** đơn vị bán được,
  **phiếu nhập** nhiều dòng (sản phẩm, số lô, hạn dùng, số lượng, giá nhập; không nhận hàng đã hết hạn), **xuất hủy** lô hết hạn/hư hỏng
  kèm lý do, danh sách mọi lô.
- **Lô tồn đầu kỳ:** kho chỉ gồm 200 sản phẩm đang bán ở POS, mỗi sản phẩm một lô `DK-<mã SP>` nhận ngày 01/08/2026. Dữ liệu nền
  không có hạn dùng và giá gốc, nên migration `007_opening_stock_costs.sql` **gán tự động** (cờ `is_demo = 1` trong database, không
  hiện trên trang): hạn dùng còn 5–29 tháng (hiện 04/2027 → 03/2029, không lô nào hết hạn) và **giá gốc = 55–70% giá bán** (lãi gộp
  30–45%, trung bình 37,2%) — giá nhập của lô = giá gốc. Giá trị tồn kho ≈ 1,27 tỷ đ. Hàng nhập qua phiếu nhập dùng hạn dùng, giá thật.

## Lợi nhuận gộp

Tab **Doanh thu & so sánh** → khung *Lợi nhuận gộp — hóa đơn bán tại quầy* ([ProfitService](frontend-pos/src/main/java/com/example/PhanTichAI/service/ProfitService.java)):
doanh thu thực thu (sau ưu đãi, đổi điểm) − **giá vốn của đúng lô đã bán** (giá nhập lô qua `order_item_lots`; hóa đơn trước khi có lô
dùng giá gốc sản phẩm; quà tặng 0 đ vẫn tính giá vốn). Có tổng, theo tháng và 10 sản phẩm lãi gộp cao nhất. Hóa đơn OnlineSales không có
giá vốn nên không tính.

## Dữ liệu bán hàng mô phỏng (để phân tích)

[database/seed_store_sales.py](database/seed_store_sales.py) tạo **50 hóa đơn bán tại quầy mô phỏng** từ 15/08 đến 13/11/2026 theo
đúng quy tắc của POS: lấy hàng theo lô (hạn gần trước), trừ kho, tích điểm theo hạng, mỗi ngày một ca đã kết của một thu ngân, một phần
hóa đơn mua theo cặp "hay mua kèm". Khách: 3 thành viên + 31 khách của dữ liệu nền quay lại + khách vãng lai (≥ 30 khách mua trong 12
tháng, đủ cho bước 7). Ca mô phỏng có ghi chú *"Dữ liệu bán hàng mô phỏng"*; seed cố định nên chạy lại cho cùng kết quả.

```powershell
python database\seed_store_sales.py          # tạo (từ chối nếu đã có)
python database\seed_store_sales.py --undo   # gỡ hết: trả hàng về đúng lô, xóa điểm, hóa đơn, ca; tính lại tổng của khách
```

Kết quả hiện tại (cùng 8 hóa đơn bán thử tại quầy): 58 hóa đơn, doanh thu thực thu 14,83 triệu đ, giá vốn 9,62 triệu đ, **lợi nhuận gộp
5,22 triệu đ (35,2%)**. **Bước 7 phát hiện lệch** với 34 khách: chi tiêu 12 tháng (M) thấp hơn dữ liệu nền 3,32 độ lệch chuẩn, 88% khách
rơi vào nhóm ngủ đông (dữ liệu nền 30%) → gợi ý xem trước mô hình mới (bước 8). Khi viết báo cáo, ghi rõ đây là dữ liệu mô phỏng.

## Đánh giá sản phẩm & phân tích cảm xúc

Theo chương 10 (Text mining & sentiment) của sách *Essentials of Marketing Analytics*
([sentiment_service.py](backend-pos/sentiment_service.py), [ReviewService](frontend-pos/src/main/java/com/example/PhanTichAI/service/ReviewService.java),
migration `006_product_reviews.sql`):

- **Khách** (trang thẻ thành viên → *Đánh giá sản phẩm đã mua*) chỉ đánh giá được sản phẩm **đã mua tại quầy**: 1–5 sao và nhận xét
  ≤ 500 ký tự, mỗi sản phẩm một đánh giá (sửa được). Lần đánh giá đầu có nhận xét từ **20 ký tự** được **+10 điểm** (mỗi đánh giá
  thưởng một lần, dòng điểm loại `BONUS`).
- **Phân tích cảm xúc chạy ngay trên máy** (không gửi lời khách ra ngoài): từ điển khen/chê cho mỹ phẩm, đọc được cả khi gõ không dấu
  (những từ dễ nhầm khi bỏ dấu như "rất/rát", "kém/kem", "hồng/hỏng/hông" chỉ được dùng trong cụm rõ nghĩa), hiểu phủ định
  ("không thơm" là chê, "không bị kích ứng" là khen), từ nhấn mạnh ("rất", "quá"), kết hợp với số sao → Khen / Trung lập / Chê;
  nhận chủ đề (mùi hương, giá, bao bì, hiệu quả, kết cấu, kích ứng da, phục vụ).
- **Cảnh báo kích ứng da:** nhận xét nhắc dị ứng, kích ứng, nổi mẩn, ngứa, rát… luôn tính là chê và hiện cảnh báo cho quản lý.
- Trang quản trị → tab **Đánh giá của khách**: số liệu, sản phẩm bị chê / báo kích ứng xếp đầu, chủ đề khách nhắc tới, danh sách nhận
  xét (lọc chê / kích ứng / đã ẩn), **ẩn** nhận xét không phù hợp. POS hiện **★ điểm trung bình** của nhận xét đang hiện trên thẻ sản phẩm.
- FastAPI tắt lúc khách gửi đánh giá: đánh giá vẫn được lưu, quản lý bấm *Phân tích đánh giá còn chờ* khi dịch vụ chạy lại.

## Kiểm thử

```powershell
cd D:\CODING\cosmetic-crm-ai\backend-pos
python -X utf8 -m unittest discover -s tests -v
cd ..\frontend-pos
mvn test
```

Test quan trọng: `test_new_customers_never_move_the_ruler_or_the_base_groups` — thêm 400 khách chi tiêu rất lớn,
mean/std/tâm nhóm và nhóm của 180 khách nền không đổi.

**Kiểm thử trên SQL Server thật (không đổi dữ liệu):** bật Spring/FastAPI rồi chạy

```powershell
$env:POS_READ_ONLY_QA="1"; mvn test                         # + GrowthIntegrationTest, AdminReadOnlyIntegrationTest
$env:POS_BROWSER_QA="1"; mvn test -Dtest=GrowthIntegrationTest  # + mở các màn hình mới bằng Chrome, ảnh trong target/growth-ui-qa
```

Đăng nhập nhân viên chỉ được giả lập trong ngữ cảnh test (không tạo tài khoản). Phần có ghi dữ liệu (thanh toán, đổi điểm, nhập/xuất
kho, bật chiến lược, đánh giá) chạy trong giao dịch và được **hoàn tác** sau mỗi test.

## Cấu trúc

```text
data/          OnlineSales.xlsx (raw), quy tắc nhóm hàng, tên tiếng Việt, prepare_dataset.py, processed/ + data_report.md
database/      schema.sql (model_versions, drift_checks, …) và build_database.py (tạo cosmetic_crm_db)
backend-pos/   FastAPI: clustering_service.py (mô hình nền LRFM), assistant_service.py, main.py, tests/
frontend-pos/  Spring Boot: POS, quản trị, tab Mô hình phân cụm (model.js), ca làm việc, doanh thu
docs/          Hướng dẫn cài đặt và hướng dẫn sử dụng (kèm ảnh giao diện)
```
