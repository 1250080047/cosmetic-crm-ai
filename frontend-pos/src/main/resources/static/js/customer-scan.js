'use strict';
// Quét mã QR thẻ thành viên bằng camera (webcam laptop hoặc camera điện thoại) rồi tra cứu khách
// đúng như khi thu ngân gõ mã khách / số điện thoại vào ô tra cứu và bấm Enter.
// Ảnh camera chỉ được đọc trong trình duyệt để tìm mã QR; không lưu và không gửi đi đâu.
(() => {
    const $ = (id) => document.getElementById(id);
    // Mã QR thẻ thành viên (đổi mỗi 60 giây), mã khách KH… hoặc số điện thoại, giống ô tra cứu.
    const CUSTOMER_KEY = /^(CM1\.KH\d{1,10}\.\d{1,12}\.[0-9A-F]{20}|KH\d{1,10}|\+?\d{8,15})$/i;
    const SCAN_EVERY_MS = 120;
    const MAX_WIDTH = 640;                                 // thu nhỏ khung hình cho nhanh, QR thẻ vẫn đủ rõ
    let stream = null, timer = 0, canvas = null, context = null, lastWarning = 0, opening = false;

    function status(text, error = false) {
        $('scanStatus').textContent = text;
        $('scanStatus').classList.toggle('error', error);
    }
    function cameraError(error) {
        switch (error?.name) {
            case 'NotAllowedError':
            case 'SecurityError':
                return 'Bạn chưa cho phép dùng camera. Bấm biểu tượng camera trên thanh địa chỉ, chọn "Cho phép" rồi bấm "Thử lại".';
            case 'NotFoundError':
            case 'OverconstrainedError':
                return 'Không tìm thấy camera trên máy này. Hãy cắm webcam hoặc gõ mã khách vào ô tra cứu.';
            case 'NotReadableError':
                return 'Camera đang được ứng dụng khác dùng (Zoom, Meet, Camera…). Hãy tắt ứng dụng đó rồi bấm "Thử lại".';
            default:
                return 'Chưa mở được camera. ' + (error?.message || '');
        }
    }

    async function open() {
        $('scanModal').style.display = 'flex';
        $('scanRetry').hidden = true;
        if (stream || opening) return;
        if (typeof window.jsQR !== 'function') { status('Chưa tải được bộ đọc mã QR. Hãy tải lại trang rồi thử lại.', true); return; }
        if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
            status('Trình duyệt chỉ cho mở camera khi trang chạy ở localhost hoặc HTTPS. Hãy mở quầy POS bằng http://localhost:8080 trên chính máy có camera.', true);
            return;
        }
        opening = true;
        status('Đang mở camera… Nếu trình duyệt hỏi quyền dùng camera, hãy chọn "Cho phép".');
        let media;
        try {
            media = await navigator.mediaDevices.getUserMedia({
                audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } } });
        } catch (error) {
            status(cameraError(error), true);
            $('scanRetry').hidden = false;
            return;
        } finally { opening = false; }
        if ($('scanModal').style.display === 'none') { media.getTracks().forEach((track) => track.stop()); return; }   // closed while asking
        stream = media;
        const video = $('scanVideo');
        // A front webcam looks natural mirrored; a phone's back camera does not. Decoding always uses the real image.
        video.classList.toggle('mirrored', stream.getVideoTracks()[0]?.getSettings().facingMode !== 'environment');
        video.srcObject = stream;
        await video.play().catch(() => {});
        status('Đưa mã QR trên thẻ thành viên (hoặc trên điện thoại của khách) vào giữa khung.');
        timer = setTimeout(scan, SCAN_EVERY_MS);
    }

    function scan() {
        timer = 0;
        const video = $('scanVideo');
        if (!stream) return;
        if (video.readyState >= 2 && video.videoWidth) {
            const scale = Math.min(1, MAX_WIDTH / video.videoWidth);
            const width = Math.round(video.videoWidth * scale), height = Math.round(video.videoHeight * scale);
            if (!canvas) { canvas = document.createElement('canvas'); context = canvas.getContext('2d', { willReadFrequently: true }); }
            if (canvas.width !== width) canvas.width = width;
            if (canvas.height !== height) canvas.height = height;
            context.drawImage(video, 0, 0, width, height);
            const code = window.jsQR(context.getImageData(0, 0, width, height).data, width, height, { inversionAttempts: 'dontInvert' });
            if (code?.data && found(code.data.trim())) return;
        }
        timer = setTimeout(scan, SCAN_EVERY_MS);
    }

    function found(text) {
        if (!CUSTOMER_KEY.test(text)) {
            if (Date.now() - lastWarning > 1500) {
                lastWarning = Date.now();
                status('Mã vừa quét không phải mã khách hàng ("' + text.slice(0, 40) + '"). Hãy quét mã trên thẻ thành viên.', true);
            }
            return false;
        }
        close(false);
        $('phoneInput').value = /^(kh|cm1\.)/i.test(text) ? text.toUpperCase() : text;
        window.lookupCustomer?.();      // same path as typing the code and pressing Enter
        return true;
    }

    function stop() {
        if (timer) clearTimeout(timer);
        timer = 0;
        stream?.getTracks().forEach((track) => track.stop());
        stream = null;
        $('scanVideo').srcObject = null;
    }
    function close(returnFocus = true) {
        stop();
        $('scanModal').style.display = 'none';
        if (returnFocus) $('scanCustomerButton').focus();
    }

    $('scanCustomerButton').addEventListener('click', open);
    $('scanClose').addEventListener('click', () => close());
    $('scanRetry').addEventListener('click', open);
    $('scanModal').addEventListener('click', (event) => { if (event.target === $('scanModal')) close(); });
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && $('scanModal').style.display === 'flex') close(); });
    document.addEventListener('visibilitychange', () => { if (document.hidden && stream) close(false); });   // never leave the camera on
})();
