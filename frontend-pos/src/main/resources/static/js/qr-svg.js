'use strict';
// Vẽ mã QR thành SVG (sắc nét ở mọi cỡ màn hình) bằng thư viện qrcode-generator 1.4.4 (MIT) lưu trong /js/vendor.
// Dùng chung cho trang thẻ thành viên và nút "Thẻ thành viên" ở quầy POS.
window.QrSvg = {
    render(text, { alphanumeric = false, quiet = 4 } = {}) {
        if (typeof window.qrcode !== 'function') throw new Error('Chưa tải được bộ tạo mã QR.');
        const qr = window.qrcode(0, 'M');
        qr.addData(text, alphanumeric ? 'Alphanumeric' : 'Byte');
        qr.make();
        const count = qr.getModuleCount(), size = count + quiet * 2, path = [];
        for (let row = 0; row < count; row++)
            for (let col = 0; col < count; col++)
                if (qr.isDark(row, col)) path.push('M' + (col + quiet) + ' ' + (row + quiet) + 'h1v1h-1z');
        const ns = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('viewBox', '0 0 ' + size + ' ' + size);
        svg.setAttribute('shape-rendering', 'crispEdges');
        svg.setAttribute('role', 'img');
        const background = document.createElementNS(ns, 'rect');
        background.setAttribute('width', size); background.setAttribute('height', size); background.setAttribute('fill', '#fff');
        const modules = document.createElementNS(ns, 'path');
        modules.setAttribute('d', path.join('')); modules.setAttribute('fill', '#000');
        svg.append(background, modules);
        return svg;
    }
};
