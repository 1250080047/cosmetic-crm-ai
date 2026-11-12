'use strict';
// Tab "Mô hình phân cụm": mô hình nền, thước đo, kiểm tra lệch (bước 7) và huấn luyện lại có kiểm soát (bước 8).
(() => {
    const $ = (id) => document.getElementById(id);
    const AI = '/api/admin/ai';
    const st = { status: null, drift: null, options: null, chart: null, selectedK: null, preview: null, busy: false, wantedK: null, optionsRequest: 0, visible: false };
    const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
    const precise = new Intl.NumberFormat('vi-VN', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
    const currency = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const finite = (value) => value !== null && value !== undefined && Number.isFinite(Number(value));
    const count = (value) => finite(value) ? number.format(Number(value)) : '—';
    const money = (value) => finite(value) ? currency.format(Number(value)) : '—';
    const day = (value) => {
        if (!value) return '—';
        const match = String(value).match(/^(\d{4})-(\d{2})-(\d{2})/);
        return match ? match[3] + '/' + match[2] + '/' + match[1] : '—';
    };
    const moment = (value) => value ? new Date(value).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) : '—';
    const featureValue = (feature, value) => {
        if (!finite(value)) return '—';
        if (feature === 'Monetary') return money(value);
        if (feature === 'Frequency') return count(Math.round(Number(value) * 10) / 10) + ' đơn';
        return count(Math.round(Number(value))) + ' ngày';
    };

    function el(tag, text, className) {
        const node = document.createElement(tag);
        if (text !== undefined && text !== null) node.textContent = String(text);
        if (className) node.className = className;
        return node;
    }
    function notify(message, error = false) {
        const node = el('div', message, 'toast' + (error ? ' error' : ''));
        $('toastRegion').append(node);
        setTimeout(() => node.remove(), error ? 9000 : 6000);
    }
    async function api(path, options = {}) {
        const response = await fetch(AI + path, { ...options, signal: options.signal || AbortSignal.timeout(150000) });
        let data;
        try { data = await response.json(); } catch { throw new Error('Phản hồi của dịch vụ không hợp lệ.'); }
        if (!response.ok || data?.success === false) {
            const detail = typeof data?.detail === 'string' ? data.detail : data?.message;
            throw new Error(detail || 'Dịch vụ trả về lỗi ' + response.status + '.');
        }
        return data;
    }
    function rows(bodyId, items, columns, empty) {
        const body = $(bodyId);
        body.replaceChildren();
        if (!items.length) {
            const tr = el('tr'), td = el('td', empty, 'table-state');
            td.colSpan = columns.length; tr.append(td); body.append(tr); return;
        }
        items.forEach((item) => {
            const tr = el('tr');
            columns.forEach((column) => {
                const td = el('td', null, column.className ? column.className(item) : (column.numeric ? 'numeric' : ''));
                const value = column.value(item);
                if (value instanceof Node) td.append(value); else td.textContent = value ?? '—';
                tr.append(td);
            });
            body.append(tr);
        });
    }
    function tableMessage(bodyId, columns, message, error = false) {
        const tr = el('tr'), td = el('td', message, 'table-state' + (error ? ' error' : ''));
        td.colSpan = columns; tr.append(td); $(bodyId).replaceChildren(tr);
    }
    function segment(name, color) {
        const wrap = el('div', null, 'segment-cell'), dot = el('span', null, 'color-dot');
        dot.style.backgroundColor = /^#[\da-f]{6}$/i.test(color || '') ? color : '#67846c';
        wrap.append(dot, el('strong', name)); return wrap;
    }
    function stat(label, value, note) {
        const card = el('article', null, 'stat-card'), top = el('div', null, 'stat-top');
        top.append(el('span', label));
        card.append(top, el('strong', value, 'stat-value'), el('div', note, 'stat-note'));
        return card;
    }
    function confirmAction(title, description, label) {
        const dialog = $('confirmDialog');
        if (dialog.open) return Promise.resolve(false);
        $('confirmTitle').textContent = title;
        $('confirmDescription').textContent = description;
        $('confirmAccept').textContent = label;
        return new Promise((resolve) => {
            const finish = (accepted) => {
                $('confirmAccept').removeEventListener('click', accept);
                $('confirmCancel').removeEventListener('click', cancel);
                dialog.removeEventListener('cancel', cancel);
                if (dialog.open) dialog.close();
                resolve(accepted);
            };
            const accept = () => finish(true);
            const cancel = (event) => { event?.preventDefault(); finish(false); };
            $('confirmAccept').addEventListener('click', accept);
            $('confirmCancel').addEventListener('click', cancel);
            dialog.addEventListener('cancel', cancel);
            dialog.showModal();
            $('confirmCancel').focus();
        });
    }
    function setBusy(busy) {
        st.busy = busy;
        const hasModel = st.status?.has_model === true;
        $('rescoreButton').disabled = busy || !hasModel;
        $('driftCheckButton').disabled = busy || !hasModel;
        $('previewButton').disabled = busy || !st.selectedK || !st.options;
        $('deployModelButton').disabled = busy || !st.preview;
        $('trainSource').disabled = busy;
        document.querySelectorAll('#trainK .k-btn').forEach((button) => { button.disabled = busy; });
    }

    // ---- status ---------------------------------------------------------------------------
    async function load() {
        $('modelStatusText').textContent = 'Đang đọc mô hình…';
        $('modelStatusText').classList.remove('error');
        try {
            st.status = await api('/model-status');
            renderStatus();
            if (st.status.has_model) {
                try { st.drift = await api('/drift'); renderDrift(); }
                catch (error) { $('driftMessage').textContent = 'Chưa kiểm tra được: ' + error.message; }
            } else {
                st.drift = null;
                renderDrift();
            }
            renderClusters();
            await loadOptions();
        } catch (error) {
            $('modelStatusText').textContent = 'Chưa đọc được mô hình. ' + error.message;
            $('modelStatusText').classList.add('error');
            ['rulerBody', 'modelClusterBody', 'driftFeatureBody', 'versionBody'].forEach((id) => tableMessage(id, 7, error.message, true));
        } finally { setBusy(false); }
    }
    function renderStatus() {
        const s = st.status;
        const extended = $('trainSource').querySelector('option[value="extended"]');
        extended.disabled = !s.has_model;
        if (!s.has_model) $('trainSource').value = 'base';
        if (!s.has_model) {
            $('modelStatusText').textContent = 'Chưa có mô hình. Bước đầu tiên: huấn luyện mô hình nền v1 trên dữ liệu OnlineSales (ngày tham chiếu ' + day(s.base_reference_date) + ') ở khung "Huấn luyện mô hình" bên dưới.';
            $('modelKpis').replaceChildren(stat('Mô hình đang áp dụng', 'Chưa có', 'Quầy POS chưa xếp nhóm khách'));
            tableMessage('rulerBody', 7, 'Thước đo sẽ xuất hiện sau khi huấn luyện mô hình nền.');
            $('trainCaption').textContent = 'Bước 2: mô hình chỉ học một lần trên dữ liệu nền. Chọn số nhóm, xem trước rồi áp dụng.';
        } else {
            $('modelStatusText').textContent = 'Đang áp dụng phiên bản ' + s.label + ' (' + s.active_k + ' nhóm), học trên ' + s.training_source + '. Hôm nay ' + day(s.today) + '.';
            $('modelKpis').replaceChildren(
                stat('Phiên bản đang áp dụng', s.label, s.active_k + ' nhóm · K-means trên L, R, F, M'),
                stat('Ngày tham chiếu', day(s.reference_date), 'Ngày "đo" khách khi mô hình học'),
                stat('Khách dùng để học', count(s.training_rows), 'Mua trong 12 tháng trước ngày tham chiếu'),
                stat('Khách hiện có', count(s.all_customers), count(s.new_customers) + ' khách chưa mua lần nào (không xếp nhóm)'),
                stat('Độ tách biệt', number.format(s.metrics?.silhouette ?? 0), 'Silhouette (cao hơn tốt hơn) · Davies–Bouldin ' + number.format(s.metrics?.davies_bouldin ?? 0)));
            $('rulerCaption').textContent = 'Học từ ' + count(s.training_rows) + ' khách của phiên bản ' + s.label + '. Các giá trị này cố định cho tới khi áp dụng phiên bản mới.';
            rows('rulerBody', s.ruler, [
                { value: (r) => r.label },
                { numeric: true, value: (r) => featureValue(r.feature, r.min) },
                { numeric: true, value: (r) => featureValue(r.feature, r.median) },
                { numeric: true, value: (r) => featureValue(r.feature, r.max) },
                { numeric: true, value: (r) => r.cap == null ? 'không cắt' : featureValue(r.feature, r.cap) },
                { numeric: true, value: (r) => precise.format(r.mean_log) },
                { numeric: true, value: (r) => precise.format(r.std_log) },
            ], 'Chưa có thước đo.');
            $('trainCaption').textContent = 'Bước 8: huấn luyện lại có kiểm soát. Bản xem trước không thay đổi gì cho tới khi bạn bấm Áp dụng.';
        }
        rows('versionBody', s.versions || [], [
            { value: (v) => v.label },
            { value: (v) => { const badge = el('span', v.status === 'ACTIVE' ? 'Đang áp dụng' : 'Đã lưu trữ', 'version-status' + (v.status === 'ACTIVE' ? ' active' : '')); return badge; } },
            { numeric: true, value: (v) => v.k },
            { value: (v) => day(v.reference_date) },
            { value: (v) => v.training_source + ' · ' + count(v.training_rows) + ' dòng' },
            { value: (v) => moment(v.activated_at) },
            { value: (v) => [v.created_by, v.reason].filter(Boolean).join(' · ') || '—' },
        ], 'Chưa có phiên bản nào.');
    }
    function renderClusters() {
        const s = st.status;
        if (!s?.has_model) { tableMessage('modelClusterBody', 6, 'Chưa có mô hình.'); return; }
        const active = new Map((st.drift?.groups || []).map((g) => [g.cluster_id, g.count]));
        $('modelClusterCaption').textContent = 'Lúc học: ' + count(s.training_rows) + ' khách dữ liệu nền. Hôm nay: mọi khách được đặt lên thước đo của ' + s.label +
            (s.scored_to ? ' (xếp lần cuối ' + moment(s.scored_to) + ')' : '') + '. Khách không mua 365 ngày được xếp vào nhóm "' +
            (s.clusters.find((c) => c.cluster_id === s.lapsed_cluster_id)?.name || '—') + '".';
        rows('modelClusterBody', s.clusters, [
            { value: (c) => segment(c.name, c.color) },
            { numeric: true, value: (c) => count(c.count) },
            { numeric: true, value: (c) => count(c.percentage) + '%' },
            { numeric: true, value: (c) => count(c.current_count) },
            { numeric: true, value: (c) => count(active.get(c.cluster_id) ?? 0) },
            { value: (c) => c.behavior_desc },
        ], 'Chưa có nhóm.');
    }
    function renderDrift() {
        const d = st.drift;
        const labels = { insufficient: 'Chưa đủ dữ liệu', stable: 'Ổn định', drift: 'Có lệch' };
        if (!d) {
            $('driftBadge').textContent = '—';
            $('driftMessage').textContent = 'Cần có mô hình nền trước khi kiểm tra lệch.';
            $('driftMessage').dataset.status = '';
            $('driftReasons').replaceChildren();
            tableMessage('driftFeatureBody', 5, 'Chưa có dữ liệu.');
            return;
        }
        $('driftBadge').textContent = labels[d.status] || d.status;
        $('driftMessage').textContent = d.message;
        $('driftMessage').dataset.status = d.status;
        $('driftCaption').textContent = 'Khách mua trong 12 tháng tính tới ' + day(d.as_of) + ': ' + count(d.sample_size) + ' (cần ≥ ' + d.min_sample +
            '). Ngưỡng: lệch trung bình > ' + number.format(d.thresholds.mean_z) + ' độ lệch chuẩn, > ' + d.thresholds.outside_share +
            '% khách ngoài khoảng đã học, hoặc một nhóm > ' + d.thresholds.group_share + '%.';
        $('driftReasons').replaceChildren(...(d.reasons || []).map((reason) => el('li', reason)));
        rows('driftFeatureBody', d.features || [], [
            { value: (f) => f.label },
            { numeric: true, value: (f) => featureValue(f.feature, f.base_median) },
            { numeric: true, value: (f) => featureValue(f.feature, f.current_median) },
            { className: (f) => 'numeric' + (Math.abs(f.mean_z) > d.thresholds.mean_z ? ' flagged-cell' : ''), value: (f) => (f.mean_z > 0 ? '+' : '') + number.format(f.mean_z) },
            { className: (f) => 'numeric' + (f.outside_percent > d.thresholds.outside_share ? ' flagged-cell' : ''), value: (f) => count(f.outside_percent) + '%' },
        ], 'Chưa có khách nào mua trong 12 tháng gần đây để so sánh.');
    }

    // ---- training options / preview -------------------------------------------------------
    async function loadOptions() {
        const sequence = ++st.optionsRequest;
        const source = $('trainSource').value;
        clearPreview();
        st.options = null;
        $('trainK').replaceChildren();
        $('trainRecommend').textContent = '';
        $('trainChartState').hidden = false;
        $('trainChartState').classList.remove('error');
        $('trainChartState').textContent = 'Đang đánh giá số nhóm…';
        setBusy(st.busy);
        try {
            const options = await api('/training-options?source=' + source);
            if (sequence !== st.optionsRequest) return;
            st.options = options;
            $('trainRecommend').textContent = 'Gợi ý: ' + options.recommended_k + ' nhóm. ' + options.recommended_reason + ' Dữ liệu: ' + options.training_source + '.';
            options.k_range.forEach((k) => {
                const button = el('button', k, 'k-btn' + (k === options.recommended_k ? ' recommended' : ''));
                button.type = 'button';
                button.dataset.k = String(k);
                button.setAttribute('aria-label', k + ' nhóm' + (k === options.recommended_k ? ', được gợi ý' : '') + (k === st.status?.active_k ? ', đang dùng' : ''));
                button.addEventListener('click', () => { if (!st.busy) selectK(k); });
                $('trainK').append(button);
            });
            const wanted = [st.wantedK, st.status?.active_k, options.recommended_k].find((k) => options.k_range.includes(k));
            st.wantedK = null;
            selectK(wanted);
            renderChart();
        } catch (error) {
            if (sequence !== st.optionsRequest) return;
            $('trainChartState').textContent = error.message;
            $('trainChartState').classList.add('error');
            st.chart?.destroy(); st.chart = null;
        } finally { setBusy(st.busy); }
    }
    function renderChart() {
        st.chart?.destroy(); st.chart = null;
        const o = st.options;
        if (typeof window.Chart === 'undefined') { $('trainChartState').textContent = 'Không tải được thư viện biểu đồ.'; return; }
        st.chart = new Chart($('trainChart'), {
            type: 'line',
            data: { labels: o.k_range.map((k) => k + ' nhóm'), datasets: [
                { label: 'Độ tách biệt (Silhouette)', data: o.silhouette_scores, borderColor: '#438f78', borderWidth: 2, yAxisID: 'silhouette', tension: .2, pointRadius: o.k_range.map((k) => k === st.selectedK ? 6 : 3) },
                { label: 'Độ phân tán (Inertia)', data: o.elbow_wcss, borderColor: '#bf9177', borderWidth: 2, yAxisID: 'inertia', tension: .2, borderDash: [4, 4], pointRadius: 3 },
            ] },
            options: { responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'index', intersect: false },
                plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
                    tooltip: { callbacks: { afterBody: (items) => 'Nhóm nhỏ nhất: ' + count(o.smallest_group_percent[items[0].dataIndex]) + '% khách · Davies–Bouldin ' + number.format(o.davies_bouldin[items[0].dataIndex]) } } },
                scales: { x: { grid: { display: false } }, silhouette: { position: 'left' }, inertia: { position: 'right', grid: { drawOnChartArea: false } } } },
        });
        $('trainChartState').hidden = true;
    }
    function selectK(k) {
        st.selectedK = k;
        clearPreview();
        document.querySelectorAll('#trainK .k-btn').forEach((button) => {
            const selected = Number(button.dataset.k) === k;
            button.classList.toggle('active', selected);
            button.setAttribute('aria-pressed', String(selected));
        });
        if (st.chart) { st.chart.data.datasets[0].pointRadius = st.options.k_range.map((value) => value === k ? 6 : 3); st.chart.update(); }
        $('previewButton').textContent = 'Xem trước ' + k + ' nhóm';
        setBusy(st.busy);
    }
    function clearPreview() {
        st.preview = null;
        $('previewResult').hidden = true;
        $('previewResult').replaceChildren();
        $('deployBox').hidden = true;
    }
    function simpleTable(headers, data, rowClass) {
        const wrap = el('div', null, 'table-scroll'), table = el('table'), head = el('tr'), body = el('tbody');
        headers.forEach((h, i) => head.append(el('th', h, i ? 'numeric' : '')));
        const thead = el('thead'); thead.append(head);
        data.forEach((cells, r) => {
            const tr = el('tr');
            cells.forEach((cell, i) => {
                const td = el('td', null, (i ? 'numeric' : '') + (rowClass ? rowClass(r, i) : ''));
                if (cell instanceof Node) td.append(cell); else td.textContent = cell;
                tr.append(td);
            });
            body.append(tr);
        });
        table.append(thead, body); wrap.append(table); return wrap;
    }
    async function preview() {
        if (st.busy || !st.selectedK) return;
        const k = st.selectedK, source = $('trainSource').value;
        setBusy(true);
        $('previewButton').textContent = 'Đang huấn luyện thử…';
        try {
            const p = await api('/train-preview?' + new URLSearchParams({ k, source }), { method: 'POST' });
            if (k !== st.selectedK || source !== $('trainSource').value) return;
            st.preview = p;
            renderPreview(p);
        } catch (error) { notify('Chưa xem trước được. ' + error.message, true); }
        finally { $('previewButton').textContent = 'Xem trước ' + st.selectedK + ' nhóm'; setBusy(false); }
    }
    function renderPreview(p) {
        const box = $('previewResult');
        box.replaceChildren();
        box.append(el('h3', 'Bản xem trước: ' + p.k + ' nhóm · ' + p.training_source));
        box.append(el('p', 'Chưa có gì thay đổi. Quầy POS vẫn dùng ' + (st.status?.label ? 'phiên bản ' + st.status.label : 'trạng thái hiện tại') + ' cho tới khi bạn bấm Áp dụng.', 'muted'));
        const old = p.old_metrics;
        const compare = (now, before, higherBetter) => {
            const span = el('span', number.format(now));
            if (before != null && now !== before) span.className = (higherBetter ? now > before : now < before) ? 'better' : 'worse';
            return span;
        };
        box.append(el('h3', 'Chất lượng chia nhóm'));
        box.append(simpleTable(['Chỉ số', old ? 'Đang dùng (' + p.old_label + ')' : '—', 'Bản xem trước'], [
            ['Độ tách biệt Silhouette (cao hơn tốt hơn)', old ? number.format(old.silhouette) : '—', compare(p.metrics.silhouette, old?.silhouette, true)],
            ['Davies–Bouldin (thấp hơn tốt hơn)', old ? number.format(old.davies_bouldin) : '—', compare(p.metrics.davies_bouldin, old?.davies_bouldin, false)],
        ]));
        box.append(el('h3', 'Thước đo mới'));
        box.append(simpleTable(['Chỉ số', 'Mean cũ', 'Mean mới', 'Std cũ', 'Std mới', 'Ngưỡng cắt mới'], p.ruler.map((r) => [
            r.label, r.old_mean_log == null ? '—' : precise.format(r.old_mean_log), precise.format(r.mean_log),
            r.old_std_log == null ? '—' : precise.format(r.old_std_log), precise.format(r.std_log), r.cap == null ? 'không cắt' : featureValue(r.feature, r.cap)])));
        box.append(el('h3', 'Các nhóm của bản xem trước'));
        box.append(simpleTable(['Nhóm', 'Khách', 'Tỷ trọng', 'Khách điển hình'], p.clusters.map((c) => [segment(c.name, c.color), count(c.count), count(c.percentage) + '%', c.behavior_desc])));
        if (p.transition) {
            const t = p.transition;
            box.append(el('h3', 'Khách chuyển nhóm (cũ → mới)'));
            box.append(el('p', 'Trên ' + count(t.observations) + ' dòng dữ liệu huấn luyện: hàng là nhóm theo ' + p.old_label + ', cột là nhóm theo bản xem trước.' +
                (t.unchanged_percent != null ? ' ' + count(t.unchanged_percent) + '% giữ nguyên số thứ tự nhóm.' : ''), 'muted'));
            box.append(simpleTable(['Nhóm cũ \\ mới', ...t.new.map((g) => g.name)], t.old.map((g, r) => [g.name, ...t.counts[r].map(count)]),
                (r, i) => i && t.old.length === t.new.length && r === i - 1 ? ' diagonal' : ''));
            box.lastChild.querySelector('table').classList.add('transition-table');
        }
        box.append(el('p', 'Khi áp dụng: phiên bản mới được lưu, phiên bản cũ chuyển sang lưu trữ; mọi khách được xếp lại; ưu đãi của nhóm nào còn ít nhất 60% thành viên được giữ, nhóm khác nhận ưu đãi mới ở trạng thái TẮT.', 'muted'));
        box.hidden = false;
        $('deployBox').hidden = false;
        $('deployReason').value = st.drift?.status === 'drift' ? 'Kiểm tra lệch ngày ' + day(st.drift.as_of) + ': ' + (st.drift.reasons || []).join('; ') : '';
    }
    async function deploy() {
        const p = st.preview;
        if (st.busy || !p) return;
        const reason = $('deployReason').value.trim();
        if (p.expected_version && !reason) { notify('Hãy ghi lý do huấn luyện lại để lưu vào lịch sử phiên bản.', true); $('deployReason').focus(); return; }
        const accepted = await confirmAction('Áp dụng mô hình ' + p.k + ' nhóm?',
            'Quầy POS sẽ dùng thước đo mới từ lần tra cứu tiếp theo và mọi khách được xếp lại nhóm.\n' +
            (p.expected_version ? 'Phiên bản đang dùng được chuyển sang lưu trữ (không xóa).' : 'Đây là mô hình nền đầu tiên (v1).'), 'Áp dụng');
        if (!accepted || st.preview !== p) return;
        setBusy(true);
        try {
            const result = await api('/deploy-model', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ previewId: p.preview_id, expectedVersion: p.expected_version, reason }) });
            notify(result.message + (result.archived_strategies ? ' ' + result.archived_strategies + ' ưu đãi cũ đã được lưu trữ; hãy kiểm tra trang Chiến lược ưu đãi.' : ''));
            try { localStorage.setItem('cosmetic-crm.model-update', JSON.stringify({ version: result.active_version, at: Date.now() })); } catch { /* optional */ }
            document.dispatchEvent(new Event('admin-clusters-applied'));
            await load();
        } catch (error) { notify('Chưa áp dụng được mô hình. ' + error.message, true); }
        finally { setBusy(false); }
    }
    async function rescore() {
        if (st.busy) return;
        setBusy(true);
        try {
            const result = await api('/rescore', { method: 'POST' });
            notify(result.message);
            document.dispatchEvent(new Event('admin-clusters-applied'));
            await load();
        } catch (error) { notify('Chưa xếp lại được. ' + error.message, true); }
        finally { setBusy(false); }
    }
    async function driftCheck() {
        if (st.busy) return;
        setBusy(true);
        try {
            st.drift = await api('/drift-check', { method: 'POST' });
            renderDrift(); renderClusters();
            notify('Đã kiểm tra và ghi nhật ký: ' + st.drift.message);
        } catch (error) { notify('Chưa kiểm tra được. ' + error.message, true); }
        finally { setBusy(false); }
    }

    $('trainSource').addEventListener('change', loadOptions);
    $('previewButton').addEventListener('click', preview);
    $('deployModelButton').addEventListener('click', deploy);
    $('rescoreButton').addEventListener('click', rescore);
    $('driftCheckButton').addEventListener('click', driftCheck);
    document.addEventListener('admin-tab', (event) => {
        st.visible = event.detail === 'model';
        if (st.visible && !st.busy) load();
    });
    document.addEventListener('admin-refresh', () => { if (st.visible && !st.busy) load(); });
    // From the overview or the assistant: the K was explored on the base data, so preview it on the base data too.
    document.addEventListener('admin-model-k', (event) => {
        st.wantedK = event.detail;
        requestAnimationFrame(() => $('trainHeading').scrollIntoView({ behavior: 'smooth', block: 'start' }));
        if ($('trainSource').value !== 'base' && !st.busy) { $('trainSource').value = 'base'; loadOptions(); return; }
        if (st.options?.k_range.includes(event.detail)) { selectK(event.detail); st.wantedK = null; }
    });
})();
