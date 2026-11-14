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

    // ---- Tab wiring -------------------------------------------------------------------------------
    const loaders = { basket: loadBasket };
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
