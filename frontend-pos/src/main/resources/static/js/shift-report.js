'use strict';
// Shared rendering and CSV export of an end-of-shift report (used by the POS and the admin page).
(() => {
    const money = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 });
    const fmt = (value) => value == null ? '—' : money.format(Number(value));
    const count = (value) => value == null ? '—' : number.format(Number(value));
    const time = (value) => value ? new Date(value).toLocaleString('vi-VN') : '—';
    const payments = { CASH: 'Tiền mặt', CARD: 'Thẻ', BANK_TRANSFER: 'Chuyển khoản', TRANSFER: 'Chuyển khoản', QR: 'Chuyển khoản', ONLINE: 'Trực tuyến' };
    const el = (tag, text, className) => {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        if (className) node.className = className;
        return node;
    };
    function table(headers, rows, empty) {
        if (!rows.length) return el('p', empty, 'empty');
        const result = el('table'), head = el('thead'), headRow = el('tr'), body = el('tbody');
        headers.forEach(([label, numeric]) => headRow.append(el('th', label, numeric ? 'num' : '')));
        head.append(headRow);
        rows.forEach((cells) => {
            const row = el('tr');
            cells.forEach(([value, numeric]) => row.append(el('td', value, numeric ? 'num' : '')));
            body.append(row);
        });
        result.append(head, body);
        return result;
    }
    function render(container, report) {
        const s = report.shift, sum = report.summary, cash = report.cash;
        container.replaceChildren();
        const wrap = el('div', null, 'shift-report');
        const open = s.status === 'OPEN';
        wrap.append(el('p', 'Ca #' + s.id + ' · ' + (s.cashierName || s.username) + ' (' + s.username + ') · ' +
            (open ? 'Đang mở từ ' + time(s.openedAt) : time(s.openedAt) + ' → ' + time(s.closedAt)) +
            (s.closedByName && !open ? ' · Kết ca bởi ' + s.closedByName : ''), 'shift-meta'));
        const kpis = el('div', null, 'shift-kpis');
        [['Số hóa đơn', count(sum.orders)], ['Doanh thu thực thu', fmt(sum.revenue)], ['Tạm tính', fmt(sum.subtotal)],
            ['Giảm giá', fmt(sum.discount)], ['Khách thành viên', count(sum.members)], ['Khách vãng lai', count(sum.walkIns)],
            ['Quà đã tặng', count(report.giftTotals.units) + ' món'], ['Giá trị quà (giá niêm yết)', fmt(report.giftTotals.retailValue)]]
            .forEach(([label, value]) => { const box = el('div'); box.append(el('span', label), el('strong', value)); kpis.append(box); });
        wrap.append(kpis);
        wrap.append(el('h4', 'Tiền mặt trong két'));
        const diff = cash.difference == null ? 'Chưa đếm' : (Number(cash.difference) > 0 ? '+' : '') + fmt(cash.difference);
        const cashTable = table([['Khoản'], ['Số tiền', true]], [
            [['Tiền đầu ca'], [fmt(cash.opening), true]], [['Thu tiền mặt bán hàng'], [fmt(cash.cashSales), true]],
            [['Phiếu thu tiền mặt trong ca'], ['+' + fmt(cash.cashIn || 0), true]], [['Phiếu chi tiền mặt trong ca'], ['−' + fmt(cash.cashOut || 0), true]],
            [['Tiền mặt phải có'], [fmt(cash.expected), true]], [['Tiền mặt đếm được'], [fmt(cash.counted), true]],
            [['Chênh lệch (cho phép ±' + fmt(cash.tolerance || 10000) + ')'], [diff, true]]], '');
        if (cash.difference != null && Number(cash.difference) !== 0)
            cashTable.querySelector('tbody tr:last-child td:last-child').className = 'num ' + (Number(cash.difference) < 0 ? 'cash-diff-negative' : 'cash-diff-positive');
        wrap.append(cashTable);
        if (cash.breakdown) {
            try {
                const notes = Object.entries(JSON.parse(cash.breakdown)).sort((a, b) => Number(b[0]) - Number(a[0]));
                wrap.append(el('p', 'Đếm theo mệnh giá: ' + notes.map(([value, pieces]) => fmt(value) + ' × ' + pieces).join(' · '), 'shift-meta'));
            } catch { /* old shifts have no breakdown */ }
        }
        const review = report.review || {};
        if (review.status === 'PENDING')
            wrap.append(el('p', '⏳ Chờ quản lý / quản trị viên xác nhận chênh lệch' + (review.reason ? ' · Lý do thu ngân ghi: “' + review.reason + '”' : ''), 'shift-review-pending'));
        else if (review.status === 'RESOLVED')
            wrap.append(el('p', '✔ Đã xác nhận: ' + (review.decisionLabel || review.decision) + (review.note ? ' · ' + review.note : '') +
                (review.reviewedBy ? ' · ' + review.reviewedBy : '') + (review.reviewedAt ? ' lúc ' + time(review.reviewedAt) : '') +
                (review.reason ? ' · Lý do thu ngân ghi: “' + review.reason + '”' : ''), 'shift-review-done'));
        if ((report.movements || []).length)
            wrap.append(el('h4', 'Phiếu thu / chi tiền mặt'), table([['Thời gian'], ['Loại'], ['Lý do'], ['Người lập'], ['Số tiền', true]],
                report.movements.map((m) => [[time(m.createdAt)], [m.kind === 'IN' ? 'Thu vào két' : 'Chi ra khỏi két'], [m.reason], [m.createdBy || ''],
                    [(m.kind === 'IN' ? '+' : '−') + fmt(m.amount), true]]), ''));
        if ((report.paymentChanges || []).length)
            wrap.append(el('h4', 'Sửa phương thức thanh toán khi đối soát'), table([['Hóa đơn'], ['Từ'], ['Thành'], ['Lý do'], ['Người sửa']],
                report.paymentChanges.map((c) => [[c.transactionNo], [payments[c.oldMethod] || c.oldMethod], [payments[c.newMethod] || c.newMethod], [c.reason], [c.changedBy || '']]), ''));
        wrap.append(el('h4', 'Theo phương thức thanh toán'), table([['Phương thức'], ['Hóa đơn', true], ['Doanh thu', true]],
            report.payments.map((row) => [[payments[row.method] || row.method], [count(row.orders), true], [fmt(row.revenue), true]]),
            'Chưa có hóa đơn trong ca.'));
        wrap.append(el('h4', 'Quà tặng đã phát'), table([['Sản phẩm'], ['Số lượng', true], ['Số hóa đơn', true], ['Giá trị niêm yết', true]],
            report.gifts.map((row) => [[row.name], [count(row.quantity), true], [count(row.orders), true], [fmt(row.retailValue), true]]),
            'Không phát quà tặng trong ca.'));
        wrap.append(el('h4', 'Sản phẩm bán nhiều nhất'), table([['Sản phẩm'], ['Số lượng', true], ['Doanh số', true]],
            report.products.map((row) => [[row.name], [count(row.quantity), true], [fmt(row.revenue), true]]), 'Chưa bán sản phẩm nào.'));
        wrap.append(el('h4', 'Danh sách hóa đơn'), table([['Mã hóa đơn'], ['Thời gian'], ['Khách'], ['Thanh toán'], ['Giảm', true], ['Thực thu', true]],
            report.orders.map((row) => [[row.transactionNo], [time(row.orderDate)], [row.customerCode ? row.customerCode + (row.customerName ? ' · ' + row.customerName : '') : 'Vãng lai'],
                [payments[row.paymentMethod] || row.paymentMethod], [fmt(row.discount), true], [fmt(row.revenue), true]]), 'Chưa có hóa đơn.'));
        if (s.note) wrap.append(el('p', 'Ghi chú: ' + s.note, 'shift-meta'));
        container.append(wrap);
    }
    function download(name, rows) {
        // Guard against spreadsheet formulas, but keep real numbers (e.g. a negative cash difference) numeric.
        const cell = (value) => { let text = String(value ?? ''); if (/^[=+@-]/.test(text) && !/^-?\d+(\.\d+)?$/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"'; };
        const url = URL.createObjectURL(new Blob(['﻿' + rows.map((row) => row.map(cell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }));
        const anchor = el('a'); anchor.href = url; anchor.download = name; document.body.append(anchor); anchor.click(); anchor.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    const header = (report) => {
        const s = report.shift;
        return [['Báo cáo ca #' + s.id], ['Nhân viên', s.cashierName, s.username], ['Mở ca', time(s.openedAt)], ['Kết ca', s.closedAt ? time(s.closedAt) : 'Đang mở'], []];
    };
    function exportRevenue(report) {
        const s = report.summary, c = report.cash;
        const rows = [...header(report), ['Chỉ số', 'Giá trị'], ['Số hóa đơn', s.orders], ['Tạm tính', s.subtotal], ['Giảm giá', s.discount],
            ['Doanh thu thực thu', s.revenue], ['Tiền đầu ca', c.opening], ['Thu tiền mặt', c.cashSales], ['Phiếu thu', c.cashIn], ['Phiếu chi', c.cashOut],
            ['Tiền mặt phải có', c.expected], ['Tiền mặt đếm được', c.counted], ['Chênh lệch', c.difference],
            ['Đối soát', report.review?.status === 'PENDING' ? 'Chờ xác nhận' : report.review?.status === 'RESOLVED' ? report.review.decisionLabel : 'Không cần'],
            [], ['Phương thức', 'Hóa đơn', 'Doanh thu']];
        report.payments.forEach((row) => rows.push([payments[row.method] || row.method, row.orders, row.revenue]));
        rows.push([], ['Mã hóa đơn', 'Thời gian', 'Mã khách', 'Tên khách', 'Thanh toán', 'Tạm tính', 'Giảm giá', 'Thực thu', 'Số quà']);
        report.orders.forEach((row) => rows.push([row.transactionNo, time(row.orderDate), row.customerCode, row.customerName,
            payments[row.paymentMethod] || row.paymentMethod, row.subtotal, row.discount, row.revenue, row.giftUnits]));
        download('doanh-thu-ca-' + report.shift.id + '.csv', rows);
    }
    function exportGifts(report) {
        const rows = [...header(report), ['Mã sản phẩm', 'Sản phẩm', 'Số lượng tặng', 'Số hóa đơn', 'Giá trị theo giá niêm yết']];
        report.gifts.forEach((row) => rows.push([row.productId, row.name, row.quantity, row.orders, row.retailValue]));
        rows.push([], ['Tổng', '', report.giftTotals.units, '', report.giftTotals.retailValue]);
        download('qua-tang-ca-' + report.shift.id + '.csv', rows);
    }
    window.ShiftReport = { render, exportRevenue, exportGifts, payments };
})();
