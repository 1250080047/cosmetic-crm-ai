# Admin, phân quyền và báo cáo doanh thu

## Bắt đầu sử dụng

1. Tạo dữ liệu và database theo README (`data/prepare_dataset.py` → `database/build_database.py`), rồi chạy Spring Boot và FastAPI. Spring tạo khóa dùng chung trong `.runtime/ai-internal.key`; FastAPI đọc khóa này để chỉ nhận lời gọi từ Spring.
2. Khi khởi động, Spring tạo các tài khoản demo nếu chưa có (mật khẩu `123456`): `admin` (quản trị viên), `manager` (quản lý), `nhanvien1`–`nhanvien3` (thu ngân). Đây là tài khoản cho buổi bảo vệ; tắt bằng `app.demo-accounts.enabled=false` và đổi mật khẩu trước khi dùng thật.
3. Đăng nhập `manager` hoặc `admin`, vào **Mô hình phân cụm**, chọn số nhóm, bấm **Xem trước** rồi **Áp dụng** để tạo mô hình nền v1. Sau đó vào **Chiến lược ưu đãi** (hoặc dùng trợ lý AI) để điền và bật ưu đãi cho từng nhóm.
4. Thu ngân đăng nhập `nhanvien…`, **mở ca** rồi bán hàng; cuối ca bấm **Kết ca** để có báo cáo.

Tài khoản tạo thêm trên giao diện vẫn cần mật khẩu từ 10 ký tự. Trang `/setup` chỉ dùng khi bảng `users` trống và chỉ mở từ chính máy chủ.

## Quyền truy cập

| Chức năng | ADMIN – quản trị viên | MANAGER – quản lý | CASHIER – thu ngân |
| --- | --- | --- | --- |
| Bán hàng, tra cứu và đăng ký khách | Có | Có | Có |
| Áp dụng ưu đãi khách hàng đã duyệt | Có | Có | Có |
| Doanh thu, hóa đơn, khách hàng, tồn kho | Có | Có | Không |
| Cập nhật sản phẩm, thêm danh mục | Có | Có | Không |
| Sửa/bật/tắt ưu đãi và sử dụng phân tích AI | Có | Có | Không |
| Huấn luyện / áp dụng phiên bản mô hình, kiểm tra lệch | Có | Có | Không |
| Tạo tài khoản, khóa, đổi quyền, đặt lại mật khẩu | Có | Không | Không |

- Kiểm tra quyền tại máy chủ cho cả trang và API. Giấu nút trên giao diện là phần bổ sung.
- Mỗi yêu cầu kiểm tra lại trạng thái và vai trò trong SQL. Khóa, đổi vai trò có hiệu lực ở yêu cầu tiếp theo. Đổi mật khẩu kết thúc các phiên dùng mật khẩu cũ.
- Không được tự khóa/hạ quyền; phải giữ ít nhất một ADMIN đang hoạt động. Khóa tài khoản được dùng thay cho xóa để giữ liên kết hóa đơn.
- Hóa đơn mới ghi `orders.cashier_id` từ phiên đăng nhập, không nhận ID thu ngân từ trình duyệt. Hóa đơn cũ thiếu ID vẫn hiển thị “Chưa ghi nhận”.
- Thu ngân không nhận `cost_price` hoặc sản phẩm ngừng bán từ API danh mục POS. Ưu đãi và giá bán vẫn được kiểm tra lại khi thanh toán.
- POS không có ô giảm tay cho bất kỳ vai trò nào: tiền giảm duy nhất là ưu đãi đã duyệt của nhóm khách (`strategies`).
  Máy chủ tự tính lại tạm tính, ưu đãi và tổng tiền; hóa đơn gửi lên với số tiền khác bị từ chối.
- Các yêu cầu ghi dùng CSRF. Phiên hết hạn sau 30 phút không hoạt động. Đăng xuất qua POST.
- Vai trò được hỗ trợ là `ADMIN`, `MANAGER`, `CASHIER` (không phân biệt hoa/thường khi đọc SQL). Không dùng quyền mặc định cho một vai trò lạ. Mật khẩu cũ không phải BCrypt cần được quản trị viên đặt lại; không hỗ trợ mật khẩu văn bản thuần.

## Chức năng admin bổ sung

- **Doanh thu & so sánh:** chọn tháng/năm; so kỳ trước hoặc cùng kỳ năm trước; biểu đồ 12 tháng; bảng số liệu; doanh thu, số đơn, giá trị đơn, giảm giá; phương thức thanh toán; nhóm hàng; sản phẩm; xuất CSV; yếu tố đóng góp và hướng kiểm tra chiến lược.
- **Sản phẩm & tồn kho:** tìm tên/mã vạch; lọc hết hàng/tồn thấp/ngừng bán; sửa tên, mã vạch, danh mục, giá bán, giá vốn hiện tại; bật/tắt bán; thêm danh mục. Ngưỡng tồn thấp là 10. Tồn kho chỉ đọc tại đây vì schema hiện chưa có sổ nhập/xuất hoặc lịch sử điều chỉnh để quản lý thay đổi tồn có chứng từ.
- **Khách hàng:** tìm theo mã khách (KH…), tên, điện thoại; lọc nhóm; phân trang tại SQL; số đơn và doanh thu tính từ hóa đơn; ngày mua đầu, lần mua cuối. Khách chưa mua lần nào hiển thị “Chưa mua hàng”.
- **Hóa đơn:** lọc ngày và phương thức (Tiền mặt, Chuyển khoản, Thẻ, Trực tuyến — đơn lịch sử của bộ dữ liệu) ngay tại SQL; ô tìm kiếm nhận mã hóa đơn, mã/tên/SĐT khách, hoặc gõ thẳng “tiền mặt”, “chuyển khoản”, “thẻ”, “trực tuyến” để lọc theo phương thức; xuất CSV phía máy chủ; bấm mã để xem dòng hàng, quà tặng giá 0, thu ngân và ca.
- **Ca làm việc:** danh sách ca theo ngày và nhân viên; xem báo cáo ca, xuất doanh thu và quà tặng (CSV), in; quản lý có thể kết ca thay nhân viên quên kết ca.
- **Ưu đãi:** hiển thị tên phân khúc thật, sắp theo chi tiêu trung bình từ thấp đến cao; bật/tắt trên trang chỉnh sửa; kiểm tra mức giảm và độ dài dữ liệu tại API.

## Mô hình phân cụm (mô hình nền, kiểm tra lệch, huấn luyện lại)

- **Mô hình chỉ học khi bấm Áp dụng một phiên bản.** Phiên bản v1 học trên dữ liệu nền OnlineSales tại ngày tham chiếu
  12/01/2021 (3.205 khách mua trong 12 tháng tới ngày đó). Ngưỡng cắt P99 (L, F, M), mean/std sau log, tâm nhóm, tên nhóm,
  Silhouette và Davies–Bouldin được lưu trong `model_versions.model_json` và hiển thị ở bảng **Thước đo của mô hình**.
- **Xếp nhóm không học lại:** mỗi khách được đo L, R, F, M hôm nay rồi đặt lên thước đo đã lưu
  (z = (log(1 + min(x, ngưỡng)) − mean) / std → tâm gần nhất). Thêm khách, thêm hóa đơn, làm mới trang hay khởi động lại
  FastAPI đều không đổi thước đo. Khách chưa mua lần nào là “Chưa mua hàng” (`segment_id` NULL, không có ưu đãi nhóm).
  Khách không mua 365 ngày thuộc nhóm có lần mua gần nhất xa nhất của mô hình (v1: “Khách hàng ngủ đông”).
- **Xem thử số nhóm ở trang Tổng quan:** ô “Chia dữ liệu nền thành bao nhiêu nhóm?” có các nút 2…10 (nút có chấm là số nhóm
  đang áp dụng, viền xanh nhạt là số nhóm được gợi ý). Chọn một số để xem biểu đồ cơ cấu và bảng chân dung của cách chia đó
  trên dữ liệu nền; cột “Khách hôm nay” cho biết bao nhiêu khách sẽ thuộc mỗi nhóm nếu áp dụng. Trợ lý AI cũng phân tích
  theo số nhóm đang xem. Không có gì được ghi vào SQL; nút **Áp dụng K nhóm…** mở trang Mô hình phân cụm với nguồn
  “Chỉ dữ liệu nền” và số nhóm đã chọn để **Xem trước** rồi **Áp dụng**.
- **Xếp lại hằng ngày:** FastAPI tự xếp lại toàn bộ khách mỗi ngày (R, L tăng theo ngày); nút **Xếp lại nhóm cho hôm nay**
  làm việc này ngay. Sau mỗi hóa đơn, POS tính lại nhóm của khách với đúng hóa đơn vừa bán.
- **Kiểm tra lệch:** so khách mua trong 12 tháng gần đây với dữ liệu nền: lệch trung bình theo độ lệch chuẩn của nền,
  tỷ lệ khách ngoài khoảng đã học, và tỷ trọng nhóm. Ngưỡng: > 0,5 độ lệch chuẩn, > 20% ngoài khoảng, một nhóm > 60%.
  Dưới 30 khách thì báo “chưa đủ dữ liệu”. **Kiểm tra & ghi nhật ký** lưu kết quả vào `drift_checks`. Kiểm tra lệch không bao giờ tự huấn luyện lại.
- **Huấn luyện lại có kiểm soát:** chọn “Dữ liệu nền + khách mua 12 tháng gần đây”, chọn số nhóm, **Xem trước**. Bản xem trước
  hiển thị chất lượng cũ/mới, thước đo cũ/mới, các nhóm mới và ma trận chuyển nhóm (cũ → mới) trên cùng dữ liệu huấn luyện;
  chưa ghi gì vào SQL. **Áp dụng** cần lý do (lưu vào lịch sử), kiểm tra phiên bản đang dùng chưa bị người khác thay,
  rồi trong một giao dịch SQL: lưu phiên bản mới (v2, v3…), chuyển phiên bản cũ sang ARCHIVED, cập nhật tên/màu nhóm,
  xếp lại mọi khách và đồng bộ đúng K ưu đãi.
- **Ưu đãi khi đổi phiên bản:** nhóm còn ít nhất 60% thành viên (Jaccard trên dữ liệu huấn luyện) giữ ưu đãi đã duyệt; nhóm khác
  nhận ưu đãi nháp 0% đang tắt, ưu đãi cũ lưu nguyên vào `strategy_history`. Hóa đơn cũ và `applied_strategy_id` giữ nguyên.
- Thứ tự nhóm là **chi tiêu trung bình tăng dần**. Tên dựa trên khách điển hình (trung vị) so với dữ liệu huấn luyện:
  Khách hàng VIP, Khách hàng trung thành, Khách hàng tiềm năng, Khách hàng triển vọng, Khách hàng mới (gắn bó ngắn,
  chưa mua nhiều), Khách hàng cần quan tâm, Khách hàng sắp ngủ đông, Khách hàng có nguy cơ rời bỏ, Khách hàng ngủ đông;
  trùng tên thì thêm mức chi tiêu. Khi quy tắc đặt tên thay đổi, FastAPI đổi tên nhóm của phiên bản đang dùng lúc khởi động
  (thước đo và nhóm của từng khách giữ nguyên, tên cũ lưu trong `model_json.previous_names`).
- POS giữ khóa đọc mô hình (`sp_getapplock`) trong lúc tính ưu đãi và kiểm tra phiên bản dự đoán đúng phiên bản ACTIVE,
  tránh dùng nhóm của phiên bản này với ưu đãi của phiên bản khác.

## Thẻ thành viên của khách

- Trang khách `/member` (mở trên điện thoại): đăng ký, đăng nhập bằng SĐT + PIN 6 số, kích hoạt thẻ bằng mã của thu ngân.
  Chuỗi bảo mật riêng (`SecurityConfig.memberSecurity`): mọi thao tác ghi cần CSRF; phiên khách chỉ giữ mã khách, không có quyền nhân viên.
- **Khách dùng mạng khác (4G):** chạy `scripts/public-member.cmd` (Cloudflare Quick Tunnel → cổng 8081). Cổng 8081 chỉ nghe trên
  127.0.0.1 và `PublicMemberGateway` chỉ cho qua trang thẻ khách; mọi đường dẫn khác trả 404. Địa chỉ thật của điện thoại lấy từ
  header `CF-Connecting-IP` (chỉ tin trên cổng 8081) để giới hạn số lần đăng ký/đăng nhập/kích hoạt. Tắt hẳn: `member.public-port=-1`.
- **Cấp mã kích hoạt** (POS, mọi vai trò đứng quầy): mã 6 số, hiệu lực 10 phút, dùng một lần; nhập sai 5 lần thì mã bị hủy.
  Dùng cho khách đã có trong hệ thống (khách dữ liệu nền, khách đăng ký tại quầy) và khi khách quên PIN.
- Đăng nhập sai PIN 5 lần: khóa 15 phút (hoặc cấp mã kích hoạt để đặt lại PIN). Tin báo lỗi không cho biết số điện thoại có tồn tại hay không.
- Mã QR trên thẻ đổi mỗi 60 giây và được máy chủ kiểm tra chữ ký; mã hết hạn báo "Nhờ khách mở lại thẻ".
- Điểm: 1 điểm / 10.000 đ thực trả, cộng khi thanh toán (cùng giao dịch hóa đơn). Hóa đơn bán tại quầy trước khi có tính năng
  được cộng điểm một lần bởi `database/migrations/001_member_points.sql`.

## Ca làm việc tại POS

- Thu ngân phải **mở ca** (nhập tiền mặt đầu ca) trước khi thanh toán; máy chủ từ chối hóa đơn khi người bán chưa có ca mở. Mỗi hóa đơn lưu `cashier_id` và `shift_id`.
- **Báo cáo ca**: số hóa đơn, tạm tính, giảm giá, thực thu; doanh thu theo phương thức (tiền mặt, chuyển khoản, thẻ; hóa đơn QR cũ tính vào chuyển khoản); tiền mặt phải có = đầu ca + thu tiền mặt, so với tiền đếm được → chênh lệch; quà tặng đã phát theo giá niêm yết lúc bán (`order_items.list_price`); sản phẩm bán nhiều; danh sách hóa đơn.
- **Kết ca**: nhập tiền mặt đếm được và ghi chú; sau khi kết ca phải mở ca mới để bán tiếp. Mỗi nhân viên chỉ có một ca mở tại một thời điểm.

Sản phẩm và giá lấy từ OnlineSales (giá trung vị quy đổi 25.600 đ/USD, làm tròn nghìn đồng); nhóm hàng gán theo từ khóa. Giá vốn không có trong dữ liệu gốc nên để trống: báo cáo không tính lợi nhuận và trợ lý AI không được nhận định về lợi nhuận.

## Đọc số liệu doanh thu

- Nguồn doanh thu là `SUM(orders.final_amount)`. Số đơn là số dòng `orders`; không gắn nhãn “đã thanh toán” vì schema chưa có trạng thái thanh toán.
- Dùng khoảng ngày bắt đầu **bao gồm** và thời điểm kết thúc **không bao gồm**, múi giờ Việt Nam. Kỳ lịch sử đủ kỳ; kỳ đang diễn ra chỉ tính đến thời điểm lập báo cáo và đối chiếu đến cùng mốc lịch. Ngày không có trong tháng/năm đối chiếu được chặn về ngày cuối tháng (ví dụ 29/02 → 28/02). Đây là so theo lịch, không bảo đảm cùng số giờ mở cửa hoặc cùng thứ trong tuần.
- Giá trị đơn trung bình = doanh thu / số đơn; tỷ lệ giảm = tổng giảm / tổng tạm tính. Khi doanh thu kỳ gốc bằng 0, phần trăm tăng trưởng hiển thị chưa có gốc, không suy diễn 100%.
- Biểu đồ luôn so 12 tháng của năm chọn với năm trước; bộ lọc tháng dùng cho các KPI, đóng góp, sản phẩm và gợi ý. Tháng chưa diễn ra hiển thị “Chưa đến kỳ”.
- Phân rã doanh thu dùng `Δdoanh thu = Δsố đơn × giá trị đơn kỳ gốc + phần thay đổi giá trị đơn`. Hai phần khớp với chênh lệch doanh thu; chưa chứng minh nguyên nhân của việc số đơn/giá trị đơn thay đổi.
- Doanh thu nhóm hàng được phân bổ từ tiền cuối hóa đơn theo tỷ trọng `order_items.revenue` của từng dòng. Đây là phép phân bổ sau giảm giá, không phải số tiền giảm chính xác cho từng SKU. Tiền chưa phân bổ được đưa vào dòng riêng để khớp tổng; báo cáo cảnh báo hóa đơn thiếu hoặc lệch chi tiết.
- Danh mục dùng thông tin sản phẩm hiện tại; schema chưa lưu danh mục tại thời điểm bán. Top sản phẩm dùng doanh số dòng hàng **trước giảm giá**, có cột số lượng giá 0 riêng.
- Không tính lợi nhuận vì `cost_price` hiện tại không phải giá vốn lịch sử của dòng bán. Schema cũng chưa mô tả hoàn tiền, hủy, công nợ, nguồn khách, chi phí marketing hay giờ mở cửa.
- Khách vãng lai được tính vào hóa đơn/doanh thu; không suy ra số người từ số đơn vãng lai. Tổng RFM có thể lớn hơn lịch sử hóa đơn được nhập vào hệ thống.
- Gợi ý dựa trên số đơn, giá trị đơn, tỷ lệ giảm và nhóm hàng biến động. Cần đối chiếu thực tế và thử nghiệm có đo lường. Tồn hiện tại không thể chứng minh thiếu hàng trong một kỳ đã qua.

## Kết nối AI

Trình duyệt gọi `/api/admin/ai/...` ở Spring. Spring kiểm tra quyền, CSRF và gắn `X-Internal-Key` khi gọi FastAPI. Các endpoint AI trực tiếp từ cổng 8000 cần khóa nội bộ, kể cả `predict`, `train-preview`, `deploy-model`, `rescore`; CORS mở cho mọi nguồn đã được bỏ.

Chạy hai dịch vụ trên cùng máy từ hai thư mục như README thì không cần thêm cấu hình. Nếu chạy riêng máy, thiết lập cùng `AI_INTERNAL_KEY` (ít nhất 32 ký tự) cho cả hai, hoặc cùng `AI_INTERNAL_KEY_FILE` trỏ tới file có quyền đọc thích hợp. Khóa chỉ dùng giữa các dịch vụ; không phân phối cho thu ngân. Kiểm tra Gemini riêng vẫn cần cấu hình mạng và khóa Gemini hiện có.

## Cơ sở dữ liệu và kiểm thử

Toàn bộ bảng (kể cả `shifts`, `model_versions`, `drift_checks`, `strategy_history`) nằm trong `database/schema.sql` và được tạo bởi `database/build_database.py`. Việc mở trang, xem trước hoặc kiểm tra lệch (không ghi nhật ký) không ghi dữ liệu phân cụm. Không chạy `build_database.py --recreate` trên database đang dùng thật vì sẽ xóa hóa đơn bán tại quầy. Hibernate giữ `ddl-auto=none`.

Kiểm thử thông thường:

```powershell
cd D:\CODING\cosmetic-crm-ai\frontend-pos
mvn test
cd D:\CODING\cosmetic-crm-ai\backend-pos
python -X utf8 -m unittest discover -s tests -v
```

Máy hiện có Java 23; tùy chọn Byte Buddy cần cho Java 23 đã được đặt sẵn trong `pom.xml` (`argLine`).

Kiểm thử tích hợp SQL và giao diện chỉ đọc (cần SQL Server và FastAPI cổng 8000 đang chạy; kiểm thử trình duyệt cần Chrome và Node):

```powershell
cd D:\CODING\cosmetic-crm-ai\frontend-pos
$env:POS_READ_ONLY_QA="1"
$env:POS_BROWSER_QA="1"
mvn -Dnet.bytebuddy.experimental=true test
Remove-Item Env:POS_READ_ONLY_QA
Remove-Item Env:POS_BROWSER_QA
```

Tài khoản giả lập chỉ tồn tại trong ngữ cảnh test, không nằm trong ứng dụng đóng gói hoặc SQL. Kiểm thử đọc API thật, xác minh doanh thu phân bổ, hiển thị desktop/mobile, chặn thao tác ghi ngoài đăng nhập/đăng xuất, so số bản ghi/checksum trước và sau của các bảng chính. Không tạo hóa đơn hoặc tài khoản giả trong dữ liệu thật. Ảnh và báo cáo được ghi vào `frontend-pos/target/` (thư mục build, tự tạo lại, không cần lưu).

Tham chiếu cơ chế sử dụng: [Spring Security form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html), [CSRF và tích hợp JavaScript](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Khóa phân cụm giữa Spring và FastAPI dùng [SQL Server sp_getapplock](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-getapplock-transact-sql?view=sql-server-ver17) với chủ sở hữu là giao dịch: POS giữ khóa chia sẻ, thao tác áp dụng và sửa ưu đãi giữ khóa độc quyền.
