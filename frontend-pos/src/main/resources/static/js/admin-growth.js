'use strict';
/* Admin tabs added for store growth: products bought together (and later inventory, campaign results, reviews). */
(() => {
    const $ = (id) => document.getElementById(id);
    const currency = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
    const money = (value) => value == null ? '—' : currency.format(value);
    const count = (value) => value == null ? '—' : number.format(value);
    const percent = (value, digits = 0) => value == null ? '—' : (value * 100).toLocaleString('vi-VN', { maximumFractionDigits: digits }) + '%';
    const normalized = (value) => String(value ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').toLowerCase();
    function el(tag, text, className) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
    function notify(message, error = false) { const node = el('div', message, 'toast' + (error ? ' error' : '')); $('toastRegion').append(node); setTimeout(() => node.remove(), 7000); }
    async function api(path, options = {}) {
        const response = await fetch('/api/admin' + path, { ...options, signal: options.signal || AbortSignal.timeout(120000) });
        let data;
        try { data = await response.json(); } catch { throw new Error('Phản hồi không hợp lệ (' + response.status + ').'); }
        if (!response.ok || data?.success === false) throw new Error(data?.message || data?.detail || 'Chưa thực hiện được yêu cầu (' + response.status + ').');
        return data;
    }
    const post = (path, body) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) });
    function stat(label, value, note) {
        const article = el('article', null, 'stat-card');
        article.append(el('div', label, 'stat-top'), el('strong', value, 'stat-value'), el('p', note, 'stat-note'));
        return article;
    }
    function rows(bodyId, items, columns, empty) {
        const body = $(bodyId);
        body.replaceChildren();
        if (!items.length) {
            const tr = el('tr'), td = el('td', empty, 'table-state'); td.colSpan = columns.length; tr.append(td); body.append(tr); return;
        }
        items.forEach((item) => {
            const tr = el('tr');
            columns.forEach((column) => {
                const td = el('td', null, column.numeric ? 'numeric' : '');
                const value = column.value(item);
                if (value instanceof Node) td.append(value); else td.textContent = value ?? '—';
                tr.append(td);
            });
            body.append(tr);
        });
    }
    const failed = (bodyId, span, message) => { const tr = el('tr'), td = el('td', message, 'table-state error'); td.colSpan = span; tr.append(td); $(bodyId).replaceChildren(tr); };

    // ---- Products bought together ----------------------------------------------------------------
    const basket = { data: null };
    async function loadBasket() {
        try {
            basket.data = await api('/ai/associations');
            renderBasket();
        } catch (error) {
            $('basketCaption').textContent = 'Chưa đọc được kết quả: ' + error.message;
            failed('basketBody', 6, error.message);
            throw error;
        }
    }
    function renderBasket() {
        const data = basket.data, usage = data.usage || {};
        const run = data.run;
        $('basketMin').textContent = count(run?.min_together ?? data.min_together ?? 30);
        $('basketCaption').textContent = run
            ? 'Lần tính gần nhất: ' + new Date(run.computed_at).toLocaleString('vi-VN') + ' · ' + count(run.basket_count) + ' hóa đơn (' +
              count(run.store_basket_count) + ' bán tại quầy)' + (run.requested_by ? ' · ' + run.requested_by : '')
            : 'Chưa có kết quả. Bấm “Tính lại” để phân tích các hóa đơn đã lưu.';
        $('basketStats').replaceChildren(
            stat('Quy tắc A → B', count(run?.rule_count ?? 0), count(data.pair_count ?? 0) + ' cặp sản phẩm, mỗi cặp 2 chiều'),
            stat('Hóa đơn tại quầy dùng gợi ý', count(usage.invoices_with_suggestion ?? 0),
                'trên ' + count(usage.store_invoices ?? 0) + ' hóa đơn tại quầy (' + percent(usage.share ?? 0, 1) + ')'),
            stat('Món bán thêm từ gợi ý', count(usage.units ?? 0), 'số lượng thu ngân thêm từ khung “Khách hay mua kèm”'),
            stat('Doanh thu từ gợi ý', money(usage.revenue ?? 0), 'tiền các món thêm từ gợi ý, trước ưu đãi hóa đơn'));
        renderBasketRules();
    }
    function renderBasketRules() {
        const search = normalized($('basketSearch').value.trim());
        const all = basket.data?.rules || [];
        const shown = all.filter((rule) => !search || normalized(rule.antecedent_name).includes(search) || normalized(rule.consequent_name).includes(search));
        $('basketCount').textContent = shown.length === all.length ? count(all.length) + ' quy tắc' : count(shown.length) + ' / ' + count(all.length) + ' quy tắc';
        rows('basketBody', shown, [
            { value: (rule) => rule.antecedent_name },
            { value: (rule) => { const span = el('span', '→ ' + rule.consequent_name, 'basket-target'); return span; } },
            { numeric: true, value: (rule) => count(rule.together) + ' HĐ' },
            { numeric: true, value: (rule) => { const strong = el('strong', percent(rule.confidence)); strong.title = count(rule.together) + ' / ' + count(rule.antecedent_count) + ' hóa đơn có A'; return strong; } },
            { numeric: true, value: (rule) => number.format(rule.lift) },
            { numeric: true, value: (rule) => percent(rule.support, 2) }
        ], basket.data?.has_rules ? 'Không có quy tắc khớp từ khóa.' : 'Chưa có quy tắc nào.');
    }
    $('basketSearch').addEventListener('input', () => basket.data && renderBasketRules());
    $('basketRecompute').addEventListener('click', async () => {
        const button = $('basketRecompute');
        button.disabled = true;
        $('basketCaption').textContent = 'Đang đếm lại các hóa đơn…';
        try {
            basket.data = await post('/ai/associations/recompute');
            renderBasket();
            loaded.add('basket');
            notify('Đã tính lại: ' + count(basket.data.run?.rule_count ?? 0) + ' quy tắc mua kèm.');
        } catch (error) {
            notify('Chưa tính lại được. ' + error.message, true);
            if (basket.data) renderBasket();
        } finally { button.disabled = false; }
    });

    // ---- Stock by lot and expiry date -----------------------------------------------------------
    const stock = { products: null, byLabel: new Map(), lotTimer: null };
    const day = (value) => value ? new Date(value).toLocaleDateString('vi-VN') : 'Không ghi hạn';
    function expiryBadge(lot) {
        if (Number(lot.remaining) <= 0) return el('span', 'Đã hết / đã hủy', 'growth-badge muted');
        if (lot.daysLeft == null) return el('span', 'Không ghi hạn', 'growth-badge muted');
        if (lot.daysLeft < 0) return el('span', 'Hết hạn ' + count(-lot.daysLeft) + ' ngày', 'growth-badge danger');
        if (lot.daysLeft <= 60) return el('span', 'Còn ' + count(lot.daysLeft) + ' ngày', 'growth-badge warn');
        return el('span', 'Còn hạn', 'growth-badge ok');
    }
    function lotLabel(lot) {
        const box = el('span', lot.lotNo);
        if (lot.demo) { const tag = el('span', 'DEMO', 'growth-badge muted'); tag.title = 'Hạn dùng giả lập của lô tồn đầu kỳ'; box.append(' ', tag); }
        return box;
    }
    const productCell = (row) => { const box = el('div'); box.append(el('strong', row.productName), el('div', row.barcode || ('#' + row.productId), 'muted')); return box; };
    async function loadStock() {
        const [overview, receipts] = await Promise.all([api('/stock/overview'), api('/stock/receipts')]);
        renderStockOverview(overview);
        renderReceipts(receipts);
        await loadLots();
        if (!stock.products) await loadStockProducts();
        if (!$('receiptLines').children.length) addReceiptLine(null, false);
    }
    function renderStockOverview(data) {
        const s = data.summary, rules = data.rules || {};
        $('stockStats').replaceChildren(
            stat('Lô sắp hết hạn', count(s.expiringLots), count(s.expiringUnits) + ' đơn vị, hạn còn ≤ ' + (rules.expiry_warning_days ?? 60) + ' ngày'),
            stat('Lô đã hết hạn', count(s.expiredLots), count(s.expiredUnits) + ' đơn vị không bán được — cần xuất hủy'),
            stat('Sản phẩm sắp hết hàng', count(s.lowStockProducts), 'còn ≤ ' + (rules.low_stock ?? 10) + ' đơn vị còn hạn'),
            stat('Giá trị tồn kho', money(s.stockValue), 'theo giá nhập của từng lô'));
        $('stockDemoNote').hidden = !Number(s.demoLots);
        $('stockDemoNote').textContent = 'Có ' + count(s.demoLots) + ' lô tồn đầu kỳ (nhãn DEMO) với hạn dùng GIẢ LẬP để minh họa cảnh báo, vì dữ liệu nền không có hạn dùng. Hàng nhập mới qua phiếu nhập dùng hạn dùng thật.';
        rows('expiryBody', data.expiring || [], [
            { value: productCell }, { value: lotLabel }, { value: (lot) => day(lot.expiryDate) },
            { numeric: true, value: (lot) => count(lot.remaining) }, { value: expiryBadge },
            { value: (lot) => disposeButton(lot, lot.daysLeft < 0 ? 'Hết hạn sử dụng' : '') }
        ], 'Không có lô nào sắp hết hạn.');
        rows('lowStockBody', data.lowStock || [], [
            { value: productCell }, { numeric: true, value: (row) => count(row.sellable) }, { numeric: true, value: (row) => count(row.stock) },
            { value: (row) => { const button = el('button', 'Nhập hàng', 'button button-secondary button-small'); button.type = 'button';
                button.addEventListener('click', () => { addReceiptLine(row.productId); $('receiptCard').scrollIntoView({ behavior: 'smooth' }); }); return button; } }
        ], 'Không có sản phẩm nào sắp hết hàng.');
    }
    function disposeButton(lot, defaultReason) {
        if (Number(lot.remaining) <= 0) return '';
        const button = el('button', 'Xuất hủy', 'button button-secondary button-small');
        button.type = 'button';
        button.addEventListener('click', async () => {
            const reason = window.prompt('Xuất hủy ' + count(lot.remaining) + ' đơn vị của lô ' + lot.lotNo + ' (' + lot.productName + ').\nLý do:', defaultReason || 'Hư hỏng');
            if (reason == null) return;
            button.disabled = true;
            try {
                const result = await post('/stock/lots/' + lot.id + '/dispose', { reason });
                notify('Đã xuất hủy ' + count(result.disposed) + ' đơn vị lô ' + lot.lotNo + '.');
                await loadStock();
            } catch (error) { notify('Chưa xuất hủy được. ' + error.message, true); button.disabled = false; }
        });
        return button;
    }
    function renderReceipts(list) {
        rows('receiptBody', list || [], [
            { value: (r) => r.receiptNo }, { value: (r) => new Date(r.createdAt).toLocaleString('vi-VN') }, { value: (r) => r.supplier || '—' },
            { numeric: true, value: (r) => count(r.lines) }, { numeric: true, value: (r) => count(r.units) }, { numeric: true, value: (r) => money(r.value) },
            { value: (r) => r.createdBy || '—' }
        ], 'Chưa có phiếu nhập nào.');
    }
    async function loadLots() {
        const search = $('lotSearch').value.trim();
        try {
            const lots = await api('/stock/lots?search=' + encodeURIComponent(search));
            $('lotCount').textContent = count(lots.length) + (lots.length >= 300 ? '+ lô (hiện 300 lô đầu)' : ' lô');
            rows('lotBody', lots, [
                { value: productCell }, { value: lotLabel }, { value: (lot) => lot.receiptNo || 'Tồn đầu kỳ' }, { value: (lot) => day(lot.expiryDate) },
                { numeric: true, value: (lot) => count(lot.received) }, { numeric: true, value: (lot) => count(lot.remaining) },
                { numeric: true, value: (lot) => count(lot.disposed) }, { value: expiryBadge }
            ], 'Không có lô khớp từ khóa.');
        } catch (error) { failed('lotBody', 8, error.message); }
    }
    async function loadStockProducts() {
        const data = await api('/products?filter=active&size=200');
        stock.products = data.items || [];
        stock.byLabel.clear();
        const list = $('stockProducts');
        list.replaceChildren();
        stock.products.forEach((product) => {
            const label = (product.barcode || ('#' + product.id)) + ' · ' + product.productName;
            stock.byLabel.set(label, product);
            list.append(new Option(label, label));
        });
    }
    function addReceiptLine(productId = null, focus = true) {
        const line = el('div', null, 'growth-line');
        const field = (label, input) => { const wrap = el('label', label); wrap.append(input); return wrap; };
        const product = document.createElement('input');
        product.setAttribute('list', 'stockProducts'); product.name = 'product'; product.placeholder = 'Gõ tên hoặc mã SP…'; product.required = true;
        if (productId != null) {
            const found = (stock.products || []).find((item) => item.id === productId);
            if (found) product.value = (found.barcode || ('#' + found.id)) + ' · ' + found.productName;
        }
        const lot = document.createElement('input'); lot.name = 'lot'; lot.maxLength = 50; lot.placeholder = 'VD: L2611A'; lot.required = true;
        const expiry = document.createElement('input'); expiry.type = 'date'; expiry.name = 'expiry';
        const tomorrow = new Date(Date.now() + 86400000); expiry.min = tomorrow.toISOString().slice(0, 10);
        const quantity = document.createElement('input'); quantity.type = 'number'; quantity.name = 'quantity'; quantity.min = '1'; quantity.max = '100000'; quantity.step = '1'; quantity.required = true;
        const cost = document.createElement('input'); cost.type = 'number'; cost.name = 'cost'; cost.min = '0'; cost.step = '1'; cost.placeholder = 'đ / đơn vị';
        const remove = el('button', '×', 'button button-secondary button-small'); remove.type = 'button'; remove.setAttribute('aria-label', 'Bỏ dòng này');
        remove.addEventListener('click', () => { line.remove(); if (!$('receiptLines').children.length) addReceiptLine(null, false); });
        line.append(field('Sản phẩm', product), field('Số lô', lot), field('Hạn dùng', expiry), field('Số lượng', quantity), field('Giá nhập', cost), remove);
        $('receiptLines').append(line);
        if (focus) (productId != null ? lot : product).focus({ preventScroll: productId == null });
    }
    function readReceipt() {
        const form = $('receiptForm');
        const lines = [...$('receiptLines').children].map((line, index) => {
            const value = (name) => line.querySelector('[name="' + name + '"]').value.trim();
            const product = stock.byLabel.get(value('product'));
            if (!product) throw new Error('Dòng ' + (index + 1) + ': chọn sản phẩm trong danh sách gợi ý.');
            return { productId: product.id, lotNo: value('lot'), expiryDate: value('expiry') || null,
                quantity: Number(value('quantity')) || 0, unitCost: value('cost') === '' ? null : Number(value('cost')) };
        });
        return { supplier: form.elements.supplier.value.trim(), note: form.elements.note.value.trim(), lines };
    }
    $('addReceiptLine').addEventListener('click', () => addReceiptLine());
    $('receiptForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        const error = $('receiptError'), button = $('saveReceipt');
        error.hidden = true;
        let receipt;
        try { receipt = readReceipt(); } catch (problem) { error.textContent = problem.message; error.hidden = false; return; }
        if (receipt.lines.some((line) => !line.expiryDate) && !window.confirm('Có dòng chưa ghi hạn dùng. Lô không ghi hạn sẽ được bán sau cùng. Vẫn lưu?')) return;
        button.disabled = true;
        try {
            const result = await post('/stock/receipts', receipt);
            notify('Đã lưu phiếu ' + result.receipt_no + ': ' + count(result.lines) + ' lô, ' + count(result.units) + ' đơn vị.');
            $('receiptForm').reset();
            $('receiptLines').replaceChildren();
            addReceiptLine(null, false);
            await loadStock();
        } catch (problem) { error.textContent = problem.message; error.hidden = false; }
        finally { button.disabled = false; }
    });
    $('lotSearch').addEventListener('input', () => { clearTimeout(stock.lotTimer); stock.lotTimer = setTimeout(loadLots, 300); });

    // ---- Offer results against a control group --------------------------------------------------
    const verdictClass = { EFFECTIVE: 'ok', NEGATIVE: 'danger', NO_DIFFERENCE: 'muted', INSUFFICIENT: 'warn' };
    const points = (value) => value == null ? '—' : (value * 100).toLocaleString('vi-VN', { maximumFractionDigits: 1, minimumFractionDigits: 1 }) + ' điểm %';
    function comparison(data) {
        const box = el('div', null, 'experiment-result');
        const table = el('table');
        const head = el('thead'), headRow = el('tr');
        ['Phía', 'Số khách', 'Quay lại mua', 'Tỉ lệ quay lại', 'Chi tiêu TB / khách'].forEach((label, i) => headRow.append(el('th', label, i ? 'numeric' : '')));
        head.append(headRow);
        const body = el('tbody');
        [['Nhận ưu đãi (90%)', data.offer], ['Nhóm đối chứng (10%)', data.control]].forEach(([label, arm]) => {
            const tr = el('tr');
            tr.append(el('td', label), el('td', count(arm.customers), 'numeric'), el('td', count(arm.returned), 'numeric'),
                el('td', percent(arm.rate, 1), 'numeric'), el('td', data.simulated ? '—' : money(arm.spend_per_customer), 'numeric'));
            body.append(tr);
        });
        table.append(head, body);
        const scroll = el('div', null, 'table-scroll'); scroll.append(table);
        const a = data.analysis;
        const verdict = el('p', a.message, 'experiment-verdict ' + (verdictClass[a.verdict] || 'muted'));
        const facts = el('p', 'Chênh lệch: ' + points(a.difference) + (a.ci_low != null ? ' · khoảng tin cậy 95%: ' + points(a.ci_low) + ' → ' + points(a.ci_high) : '') +
            (a.p_value != null ? ' · p = ' + Number(a.p_value).toLocaleString('vi-VN', { maximumFractionDigits: 4 }) : ''), 'growth-note');
        const need = el('p', 'Để phát hiện chênh lệch 5 điểm % (từ ' + percent(a.needed_base_rate, 1) + ') với độ tin cậy 95% và độ mạnh 80%, cần khoảng ' +
            count(a.needed_per_arm_for_5_points) + ' khách ở MỖI phía (nhóm đối chứng 10% → nhóm khoảng ' + count(a.needed_per_arm_for_5_points * 10) + ' khách).', 'growth-note');
        box.append(scroll, verdict, facts, need);
        return box;
    }
    async function loadExperiments() {
        const data = await api('/experiments');
        const list = $('experimentList');
        list.replaceChildren();
        const experiments = data.experiments || [];
        $('experimentCaption').textContent = experiments.length
            ? count(experiments.length) + ' lần đo gần nhất · nhóm đối chứng ' + data.control_percent + '% · đo trong ' + data.window_days + ' ngày.'
            : 'Chưa có lần đo nào. Bật một chiến lược ở tab Chiến lược ưu đãi để bắt đầu đo.';
        experiments.forEach((experiment) => {
            const card = el('section', null, 'card');
            const heading = el('div', null, 'card-heading'), left = el('div');
            const title = el('h2', experiment.title + ' ');
            title.append(el('span', experiment.running ? 'Đang đo · ngày ' + Math.min(experiment.days_measured, experiment.windowDays) + '/' + experiment.windowDays
                : 'Đã kết thúc', 'growth-badge ' + (experiment.running ? 'ok' : 'muted')));
            left.append(title, el('p', 'Bắt đầu ' + new Date(experiment.startedAt).toLocaleString('vi-VN') + (experiment.startedBy ? ' · ' + experiment.startedBy : '') +
                (experiment.endedAt ? ' · kết thúc ' + new Date(experiment.endedAt).toLocaleString('vi-VN') : '') + ' · ' + count(experiment.cohortSize) + ' khách trong nhóm' +
                (experiment.window_complete ? '' : ' · kết quả tạm thời, chưa đủ ' + experiment.windowDays + ' ngày'), 'muted'));
            heading.append(left);
            card.append(heading, comparison(experiment));
            list.append(card);
        });
    }
    $('simulationForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        const form = event.target, button = $('simulateButton');
        button.disabled = true;
        try {
            const data = await post('/experiments/simulate', {
                groupSize: Number(form.elements.groupSize.value), baseRate: Number(form.elements.baseRate.value) / 100,
                uplift: Number(form.elements.uplift.value) / 100 });
            const result = $('simulationResult');
            result.replaceChildren(el('p', 'MÔ PHỎNG với số liệu giả định — không phải kết quả của cửa hàng. Mỗi lần chạy cho kết quả hơi khác do ngẫu nhiên.', 'growth-warning'), comparison(data));
        } catch (error) { notify('Chưa chạy được mô phỏng. ' + error.message, true); }
        finally { button.disabled = false; }
    });

    // ---- Tab wiring -------------------------------------------------------------------------------
    const loaders = { basket: loadBasket, stock: loadStock, experiments: loadExperiments };
    const loaded = new Set();
    let current = 'overview';
    async function load(tab, force = false) {
        if (!loaders[tab] || (!force && loaded.has(tab))) return;
        try { await loaders[tab](); loaded.add(tab); } catch { /* the tab shows the error */ }
    }
    window.AdminGrowth = { register(tab, loader) { loaders[tab] = loader; }, reload(tab) { loaded.delete(tab); return load(tab, true); },
        el, api, post, stat, rows, failed, notify, money, count, percent, normalized };
    document.addEventListener('admin-tab', (event) => { current = event.detail; load(current); });
    document.addEventListener('admin-refresh', () => { loaded.clear(); load(current, true); });
})();
