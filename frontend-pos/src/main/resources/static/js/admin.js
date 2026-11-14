'use strict';

(() => {
    const $ = (id) => document.getElementById(id);
    const API = '/api/admin';
    const AI = '/api/admin/ai';
    const state = {
        metrics: null, selectedK: null, clusters: [], clustersReady: false,
        activeK: null, activeVersion: null, activeClusters: [], activeSynchronized: false,
        strategyDirty: false, strategyStale: false,
        orders: null, products: null, strategies: null, page: 1, pageSize: 20,
        lineChart: null, pieChart: null, clusterRequest: 0, refreshBusy: false, orderRequest: 0, orderSummary: null,
        chatBusy: false, mutationBusy: false, chatHistory: [], modelStatus: null,
        viewK: null, analyses: new Map(), analysisRequest: 0
    };
    const moneyFormat = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const countFormat = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
    const dateFormat = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
    const dayFormat = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });
    const day = (value) => {
        if (!value) return '—';
        const match = String(value).match(/^(\d{4})-(\d{2})-(\d{2})$/);
        const parsed = match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : new Date(value);
        return Number.isNaN(parsed.getTime()) ? '—' : dayFormat.format(parsed);
    };
    const numeric = (value) => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value));
    const money = (value) => numeric(value) ? moneyFormat.format(Number(value)) : '—';
    const count = (value) => numeric(value) ? countFormat.format(Number(value)) : '—';
    const date = (value) => {
        if (!value) return '—';
        const parsed = new Date(value);
        return Number.isNaN(parsed.getTime()) ? '—' : dateFormat.format(parsed);
    };
    const normalized = (value) => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').toLowerCase();
    const color = (value) => /^#[\da-f]{6}$/i.test(value || '') ? value : '#67846c';
    // Segment names given by the clustering service (same list as backend-pos/customer_language.py).
    const SEGMENT_NAMES = new Set(['Khách hàng VIP', 'Khách hàng trung thành', 'Khách hàng tiềm năng', 'Khách hàng triển vọng',
        'Khách hàng mới', 'Khách hàng cần quan tâm', 'Khách hàng sắp ngủ đông', 'Khách hàng có nguy cơ rời bỏ', 'Khách hàng ngủ đông', 'Chưa mua hàng']);
    // Short names used by model v1 before 12/11/2026.
    const PREVIOUS_NAMES = { 'vip': 'Khách hàng VIP', 'mua deu': 'Khách hàng trung thành', 'tiem nang': 'Khách hàng tiềm năng',
        'mua gan day': 'Khách hàng triển vọng', 'moi quen': 'Khách hàng mới', 'khach quen': 'Khách hàng cần quan tâm',
        'mua thinh thoang': 'Khách hàng sắp ngủ đông', 'can giu chan': 'Khách hàng có nguy cơ rời bỏ', 'lau chua ghe': 'Khách hàng ngủ đông', 'khach moi': 'Chưa mua hàng' };
    const segmentLabel = (name) => {
        const text = String(name || '').trim();
        const cut = text.indexOf(' · ');
        if (cut >= 0) return segmentLabel(text.slice(0, cut)) + text.slice(cut);
        if (SEGMENT_NAMES.has(text)) return text;
        const value = normalized(text);
        if (PREVIOUS_NAMES[value]) return PREVIOUS_NAMES[value];
        if (/^(khach )?(ngu dong\s*\/\s*roi bo|at-risk|lost)$/.test(value)) return 'Khách hàng ngủ đông';
        if (/^(vip\s*\/\s*champions|champions)$|^vip kim cuong/.test(value)) return 'Khách hàng VIP';
        if (/^khach hang tiem nang$/.test(value)) return 'Khách hàng tiềm năng';
        return text || 'Chưa có tên phân khúc';
    };
    const orderedClusters = (clusters) => clusters.map((item) => ({ ...item, name: segmentLabel(item.name) }))
        .sort((a, b) => Number(a.monetary_spent ?? 0) - Number(b.monetary_spent ?? 0) || Number(a.rank ?? a.cluster_id) - Number(b.rank ?? b.cluster_id));
    const strategyName = (strategy) => segmentLabel(strategy?.segmentName ||
        state.activeClusters.find((cluster) => cluster.cluster_id === strategy?.segmentId)?.name || 'Chưa đồng bộ phân khúc');
    function updateClusterNotes() {
        if (state.activeSynchronized && state.activeK) {
            $('strategySyncNote').textContent = state.activeK + ' phân khúc của mô hình đang áp dụng · Chi tiêu trung bình từ thấp đến cao.';
            $('strategySyncNote').classList.remove('error');
        } else {
            $('strategySyncNote').textContent = state.activeK
                ? 'Mô hình và danh sách ưu đãi chưa đồng bộ. Vào trang Mô hình phân cụm để kiểm tra.'
                : 'Chưa có mô hình nền. Vào trang Mô hình phân cụm để huấn luyện mô hình v1 trước khi tạo ưu đãi.';
            $('strategySyncNote').classList.add('error');
        }
    }
    function setActiveClusters(data) {
        state.activeK = Number.isInteger(data?.active_k) ? data.active_k : null;
        state.activeVersion = data?.active_version ?? null;
        state.activeClusters = Array.isArray(data?.clusters) ? orderedClusters(data.clusters) : [];
        state.activeSynchronized = data?.synchronized === true && state.activeClusters.length === state.activeK;
        updateClusterNotes();
    }
    async function refreshStrategyMetadata() {
        if (state.refreshBusy || state.mutationBusy) return;
        state.strategyStale = true;
        $('strategyFields').disabled = true;
        $('saveStrategyButton').disabled = true;
        try {
            const active = await request(AI + '/model-status');
            if (state.mutationBusy) return;
            setActiveClusters(active);
            await loadStrategies();
        } catch (error) {
            $('strategySyncNote').textContent = 'Chưa kiểm tra được phân cụm đang dùng. ' + error.message;
            $('strategySyncNote').classList.add('error');
        }
    }
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = String(text);
        return node;
    };

    async function request(url, options = {}) {
        const response = await fetch(url, { ...options, signal: options.signal || AbortSignal.timeout(45000) });
        let data;
        try { data = await response.json(); } catch { throw new Error('Phản hồi của dịch vụ không hợp lệ.'); }
        if (!response.ok || data?.success === false) {
            const detail = typeof data?.detail === 'string' ? data.detail : data?.message;
            throw new Error(detail || (response.status === 404 ? 'Không tìm thấy API. Hãy khởi động lại dịch vụ sau khi cập nhật.' : 'Dịch vụ trả về lỗi ' + response.status + '.'));
        }
        return data;
    }
    function tableState(bodyId, columns, message, error = false) {
        const row = element('tr');
        const cell = element('td', 'table-state' + (error ? ' error' : ''), message);
        cell.colSpan = columns;
        row.append(cell);
        $(bodyId).replaceChildren(row);
    }
    function sectionState(id, message, error = false) {
        $(id).textContent = message;
        $(id).classList.toggle('error', error);
        $(id).hidden = false;
    }
    function connection(id, status, message) {
        const node = $(id);
        node.dataset.state = status;
        node.replaceChildren(element('span', 'status-dot'), document.createTextNode(message));
    }
    function toast(message, error = false) {
        const node = element('div', 'toast' + (error ? ' error' : ''), message);
        $('toastRegion').append(node);
        setTimeout(() => node.remove(), error ? 9000 : 6000);
    }
    function confirmAction(title, description, label = 'Xác nhận') {
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
                dialog.close();
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
    function switchTab(tab) {
        const enteringStrategies = tab === 'strategies' && $('tab-strategies').hidden;
        const titles = {
            overview: ['Tổng quan', 'Tổng quan kinh doanh', 'Theo dõi hoạt động cửa hàng và hiểu rõ từng nhóm khách hàng.'],
            model: ['Mô hình phân cụm', 'Mô hình nền & kiểm tra lệch', 'Mô hình chỉ học một lần trên dữ liệu nền; khách mới được đặt lên thước đo cũ.'],
            strategies: ['Chiến lược ưu đãi', 'Ưu đãi đúng người, đúng lúc', 'Xem và điều chỉnh các chiến lược đang được lưu cho cửa hàng.'],
            basket: ['Gợi ý mua kèm', 'Sản phẩm hay được mua cùng nhau', 'Phân tích giỏ hàng: khách mua món này thường mua thêm món nào — gợi ý cho thu ngân ở POS.'],
            orders: ['Lịch sử hóa đơn', 'Lịch sử hóa đơn', 'Tra cứu giao dịch đã ghi nhận trong cơ sở dữ liệu cửa hàng.'],
            revenue: ['Doanh thu & so sánh', 'Hiểu biến động doanh thu', 'So sánh tháng hoặc năm, xác định yếu tố đóng góp và chọn hướng kiểm tra.'],
            products: ['Sản phẩm & tồn kho', 'Danh mục và tồn kho', 'Quản lý thông tin bán hàng và theo dõi sản phẩm cần bổ sung.'],
            customers: ['Khách hàng', 'Hiểu lịch sử khách hàng', 'Tra cứu khách theo mã, nhóm và lịch sử mua hàng.'],
            shifts: ['Ca làm việc', 'Ca làm việc & báo cáo kết ca', 'Theo dõi nhân viên đứng quầy, doanh thu từng ca, tiền mặt và quà tặng đã phát.'],
            users: ['Tài khoản & quyền', 'Đúng người, đúng quyền', 'Quản lý tài khoản và quyền truy cập của nhân sự cửa hàng.']
        };
        if (!titles[tab]) return;
        if (tab === 'users' && window.storeUser?.role !== 'ADMIN') return;
        document.querySelectorAll('.tab-panel').forEach((node) => { node.hidden = node.id !== 'tab-' + tab; });
        document.querySelectorAll('.nav-item').forEach((node) => {
            const active = node.dataset.tab === tab;
            node.classList.toggle('active', active);
            if (active) node.setAttribute('aria-current', 'page');
            else node.removeAttribute('aria-current');
        });
        $('breadcrumbPage').textContent = titles[tab][0];
        $('pageTitle').textContent = titles[tab][1];
        $('pageDescription').textContent = titles[tab][2];
        document.dispatchEvent(new CustomEvent('admin-tab', { detail: tab }));
        if (enteringStrategies) refreshStrategyMetadata();
        if (tab === 'overview') {
            requestAnimationFrame(() => { state.lineChart?.resize(); state.pieChart?.resize(); });
        }
    }

    async function loadStore() {
        try {
            const summary = await request(API + '/orders/summary');
            state.orderSummary = summary;
            $('kpiOrders').textContent = count(summary.orders);
            $('kpiRevenue').textContent = money(summary.revenue);
            $('revenueNote').textContent = 'Sau giảm giá · ' + day(summary.firstOrder) + ' → ' + day(summary.lastOrder);
            $('ordersNote').textContent = 'Toàn bộ hóa đơn đã ghi nhận';
            $('kpiProducts').textContent = count(summary.products.active);
            $('productsNote').textContent = 'Đang bày bán · ' + count(summary.products.total) + ' mã trong lịch sử';
            ['revenueNote', 'ordersNote', 'productsNote'].forEach((id) => $(id).classList.remove('error'));
            connection('databaseStatus', 'success', 'Đã đọc dữ liệu SQL Server');
            $('lastSync').textContent = 'Đọc dữ liệu cửa hàng lúc ' + new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
            await loadOrders();
        } catch (error) {
            ['kpiOrders', 'kpiRevenue', 'kpiProducts'].forEach((id) => { $(id).textContent = '—'; });
            $('revenueNote').textContent = 'Chưa đọc được dữ liệu hóa đơn';
            $('revenueNote').classList.add('error');
            connection('databaseStatus', 'error', 'Dữ liệu cửa hàng chưa đầy đủ');
            tableState('ordersTableBody', 7, error.message, true);
            $('lastSync').textContent = 'Chưa cập nhật dữ liệu cửa hàng';
        }
    }

    const DRIFT_BADGE = { insufficient: 'Chưa đủ khách mới để kết luận lệch', stable: 'Dữ liệu ổn định so với nền', drift: 'Phát hiện lệch dữ liệu' };
    function fact(label, value) {
        const node = element('span', 'model-fact');
        node.append(element('small', '', label), element('strong', '', value));
        return node;
    }
    function analysisUnavailable(message, error = true) {
        state.clustersReady = false;
        state.clusters = [];
        state.selectedK = null;
        state.pieChart?.destroy(); state.pieChart = null;
        $('kpiCustomers').textContent = '—';
        $('customersNote').textContent = error ? 'Chưa đọc được mô hình phân cụm' : 'Chưa có mô hình nền';
        $('customersNote').classList.toggle('error', error);
        $('modelSummary').textContent = message;
        $('modelFacts').replaceChildren();
        $('aiRecommendBadge').querySelector('span').textContent = error ? 'Chưa đọc được trạng thái' : 'Chưa có mô hình';
        $('distributionLegend').replaceChildren();
        $('chatKBadge').textContent = 'K = —';
        $('chatKTitle').textContent = 'Chưa có mô hình phân cụm';
        $('distributionSubtitle').textContent = 'Chưa có mô hình';
        $('profilesSummary').textContent = 'Chưa có dữ liệu phân khúc';
        $('analysisSummary').textContent = message;
        $('analysisMode').textContent = '—';
        $('analysisMode').dataset.mode = '';
        $('applyAnalysisK').hidden = true;
        sectionState('distributionState', message, error);
        tableState('clusterProfileTableBody', 8, message, error);
    }
    async function loadAnalysis() {
        connection('aiStatus', 'loading', 'Đang đọc kết quả phân tích AI');
        state.analyses = new Map();
        try {
            const status = await request(AI + '/model-status');
            state.modelStatus = status;
            setActiveClusters(status.has_model ? status : null);
            if (!status.has_model) {
                analysisUnavailable('Chưa có mô hình nền. Mở trang Mô hình phân cụm để huấn luyện mô hình v1 trên dữ liệu OnlineSales.', false);
                connection('aiStatus', 'success', 'Dịch vụ AI sẵn sàng · chưa có mô hình');
                await loadMetricsChart();
                await showDefaultK();
                return;
            }
            if (!Array.isArray(status.clusters) || status.clusters.length !== status.active_k)
                throw new Error('Dịch vụ AI chưa trả đủ nhóm của mô hình đang áp dụng.');
            $('kpiCustomers').textContent = count(status.all_customers);
            $('customersNote').textContent = count(status.training_rows) + ' khách dữ liệu nền dùng để học · ' + count(status.new_customers) + ' khách chưa mua lần nào';
            $('customersNote').classList.remove('error');
            $('modelSummary').textContent = 'Phiên bản ' + status.label + ' · ' + status.active_k + ' nhóm · ' + status.training_source + '.';
            $('modelFacts').replaceChildren(
                fact('Phiên bản', status.label),
                fact('Ngày tham chiếu', day(status.reference_date)),
                fact('Số nhóm', String(status.active_k)),
                fact('Độ tách biệt', countFormat.format(status.metrics?.silhouette ?? 0)),
                fact('Xếp nhóm lần cuối', status.scored_to ? date(status.scored_to) : '—'));
            connection('aiStatus', 'success', 'Đã đọc mô hình ' + status.label);
            request(AI + '/drift').then((drift) => {
                $('aiRecommendBadge').querySelector('span').textContent = DRIFT_BADGE[drift.status] || 'Chưa kiểm tra lệch';
                $('aiRecommendBadge').title = drift.message || '';
                $('aiRecommendBadge').dataset.status = drift.status || '';
            }).catch(() => { $('aiRecommendBadge').querySelector('span').textContent = 'Chưa kiểm tra được lệch dữ liệu'; });
            await loadMetricsChart();
            await showDefaultK();
        } catch (error) {
            state.metrics = null;
            state.lineChart?.destroy(); state.lineChart = null;
            $('analysisK').replaceChildren();
            analysisUnavailable(error.message);
            sectionState('metricsState', error.message, true);
            connection('aiStatus', 'error', 'Chưa đọc được dữ liệu AI');
        }
    }
    async function loadMetricsChart() {
        try {
            const metrics = await request(AI + '/training-options?source=base');
            if (!Array.isArray(metrics.k_range) || metrics.k_range.length !== metrics.silhouette_scores?.length)
                throw new Error('Dịch vụ AI chưa có đầy đủ chỉ số đánh giá.');
            state.metrics = metrics;
            $('metricsSubtitle').textContent = 'Dữ liệu nền ' + count(metrics.total_customers) + ' khách · gợi ý chia ' + metrics.recommended_k + ' nhóm' +
                (state.activeK ? ' · đang dùng ' + state.activeK + ' nhóm' : '');
            renderTechnicalChart();
        } catch (error) {
            state.metrics = null;
            sectionState('metricsState', error.message, true);
        }
    }
    function renderTechnicalChart() {
        state.lineChart?.destroy();
        state.lineChart = null;
        if (typeof window.Chart === 'undefined') {
            sectionState('metricsState', 'Không tải được thư viện biểu đồ. Bảng phân khúc vẫn hiển thị dữ liệu.');
            return;
        }
        const metrics = state.metrics;
        state.lineChart = new Chart($('clusteringChart'), {
            type: 'line',
            data: {
                labels: metrics.k_range.map((k) => 'K=' + k),
                datasets: [
                    { label: 'Inertia', data: metrics.elbow_wcss, borderColor: '#bf9177', backgroundColor: '#bf917711', borderWidth: 2, pointRadius: pointSizes(), pointHoverRadius: 6, yAxisID: 'inertia', tension: .22, fill: true },
                    { label: 'Silhouette', data: metrics.silhouette_scores, borderColor: '#438f78', borderWidth: 2, pointRadius: pointSizes(), pointHoverRadius: 6, yAxisID: 'silhouette', tension: .22, borderDash: [4, 4] }
                ]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                animation: false,
                locale: 'vi-VN',
                interaction: { mode: 'index', intersect: false },
                onClick: (event, items) => { if (items.length) showK(metrics.k_range[items[0].index]); },
                onHover: (event, items) => { if (event.native?.target) event.native.target.style.cursor = items.length ? 'pointer' : 'default'; },
                plugins: { legend: { display: false }, tooltip: { callbacks: {
                    title: (items) => { const k = metrics.k_range[items[0].dataIndex]; return 'Chia ' + k + ' nhóm' + (k === state.activeK ? ' (đang áp dụng)' : '') + (k === metrics.recommended_k ? ' · gợi ý' : ''); },
                    afterBody: (items) => ['Nhóm nhỏ nhất: ' + count(metrics.smallest_group_percent[items[0].dataIndex]) + '% khách · Davies–Bouldin ' + countFormat.format(metrics.davies_bouldin[items[0].dataIndex]), 'Bấm để xem cách chia này'] } } },
                scales: {
                    x: { grid: { display: false }, border: { display: false }, ticks: { color: '#75826e', font: { size: 10 }, maxRotation: 0 } },
                    inertia: { position: 'left', grid: { color: '#edf0e8' }, border: { display: false }, ticks: { color: '#75826e', font: { size: 10 }, maxTicksLimit: 5 } },
                    silhouette: { position: 'right', grid: { drawOnChartArea: false }, border: { display: false }, ticks: { color: '#597d65', font: { size: 10 }, maxTicksLimit: 5 } }
                }
            }
        });
        $('metricsState').hidden = true;
    }
    function pointSizes() {
        return (state.metrics?.k_range || []).map((k) => k === state.viewK ? 7 : 3);
    }

    // ---- choosing how many groups to look at (view only: deploying stays on the model tab) ----------------
    function renderKButtons() {
        const range = state.metrics?.k_range || (state.activeK ? [state.activeK] : []);
        $('analysisK').replaceChildren(...range.map((k) => {
            const recommended = k === state.metrics?.recommended_k, inUse = k === state.activeK;
            const button = element('button', 'k-btn' + (recommended ? ' recommended' : '') + (inUse ? ' in-use' : ''), k);
            button.type = 'button';
            button.dataset.k = String(k);
            button.title = k + ' nhóm' + (inUse ? ' · đang áp dụng' : '') + (recommended ? ' · gợi ý' : '');
            button.setAttribute('aria-label', button.title);
            button.addEventListener('click', () => showK(k));
            return button;
        }));
    }
    function markK(k) {
        document.querySelectorAll('#analysisK .k-btn').forEach((button) => {
            const selected = Number(button.dataset.k) === k;
            button.classList.toggle('active', selected);
            button.setAttribute('aria-pressed', String(selected));
        });
        if (state.lineChart) {
            state.lineChart.data.datasets.forEach((dataset) => { dataset.pointRadius = pointSizes(); });
            state.lineChart.update('none');
        }
    }
    async function showDefaultK() {
        renderKButtons();
        const range = state.metrics?.k_range || [];
        const k = [state.viewK, state.activeK, state.metrics?.recommended_k].find((value) => range.includes(value)) ?? state.activeK;
        await showK(k);
    }
    async function showK(k) {
        if (!Number.isInteger(k)) return;
        const sequence = ++state.analysisRequest;
        state.viewK = k;
        markK(k);
        const status = state.modelStatus;
        const usingActive = status?.has_model === true && k === state.activeK;
        let data = usingActive ? status : state.analyses.get(k);
        if (!data) {
            $('analysisK').setAttribute('aria-busy', 'true');
            $('analysisSummary').textContent = 'Đang chia ' + count(state.metrics?.total_customers) + ' khách dữ liệu nền thành ' + k + ' nhóm…';
            sectionState('distributionState', 'Đang chia dữ liệu nền thành ' + k + ' nhóm…');
            tableState('clusterProfileTableBody', 8, 'Đang chia dữ liệu nền thành ' + k + ' nhóm…');
            try {
                data = await request(AI + '/base-analysis?k=' + k, { signal: AbortSignal.timeout(120000) });
                if (!Array.isArray(data.clusters) || data.clusters.length !== k) throw new Error('Dịch vụ AI chưa trả đủ ' + k + ' nhóm.');
                state.analyses.set(k, data);
            } catch (error) {
                if (sequence !== state.analysisRequest) return;
                $('analysisK').removeAttribute('aria-busy');
                state.clustersReady = false; state.clusters = []; state.selectedK = null;
                state.pieChart?.destroy(); state.pieChart = null;
                $('distributionLegend').replaceChildren();
                $('analysisSummary').textContent = 'Chưa chia được ' + k + ' nhóm. ' + error.message;
                sectionState('distributionState', error.message, true);
                tableState('clusterProfileTableBody', 8, error.message, true);
                return;
            }
            if (sequence !== state.analysisRequest) return;
            $('analysisK').removeAttribute('aria-busy');
        }
        renderView(k, data, usingActive);
    }
    function renderView(k, data, usingActive) {
        state.selectedK = k;
        state.clusters = orderedClusters(data.clusters);
        state.clustersReady = true;
        const metrics = state.metrics, index = metrics ? metrics.k_range.indexOf(k) : -1, status = state.modelStatus;
        const quality = index >= 0 ? 'độ tách biệt Silhouette ' + countFormat.format(metrics.silhouette_scores[index]) +
            ', Davies–Bouldin ' + countFormat.format(metrics.davies_bouldin[index]) + ', nhóm nhỏ nhất ' + count(metrics.smallest_group_percent[index]) + '% khách' : '';
        $('analysisMode').textContent = usingActive ? 'Đang áp dụng · ' + data.label : 'Xem thử · chưa áp dụng';
        $('analysisMode').dataset.mode = usingActive ? 'active' : 'preview';
        $('analysisSummary').textContent = 'Đang xem ' + k + ' nhóm' + (k === metrics?.recommended_k ? ' (gợi ý)' : '') + (quality ? ': ' + quality : '') + '. ' +
            (status?.has_model ? 'Quầy POS đang dùng ' + status.active_k + ' nhóm (' + status.label + ').' : 'Chưa có mô hình áp dụng cho quầy POS.');
        const apply = $('applyAnalysisK');
        apply.hidden = usingActive;
        apply.querySelector('span').textContent = status?.has_model ? 'Áp dụng ' + k + ' nhóm…' : 'Huấn luyện mô hình ' + k + ' nhóm…';
        $('distributionSubtitle').textContent = (usingActive ? 'Lúc ' + data.label + ' học: ' : 'Xem thử trên dữ liệu nền: ') + count(data.training_rows) + ' khách · chi tiêu tăng dần';
        $('selectedKTag').textContent = (usingActive ? data.label : 'Xem thử') + ' · K = ' + k;
        $('profilesSubtitle').textContent = usingActive
            ? 'Số liệu trung bình của khách lúc mô hình ' + data.label + ' học và số khách đang thuộc nhóm hôm nay.'
            : 'Bản xem thử ' + k + ' nhóm trên dữ liệu nền (chưa áp dụng). Cột "Khách hôm nay" là số khách sẽ thuộc mỗi nhóm nếu áp dụng cách chia này.';
        $('chatKBadge').textContent = 'K = ' + k;
        $('chatKTitle').textContent = usingActive ? 'Theo mô hình ' + data.label + ' (' + k + ' nhóm)' : 'Theo bản xem thử ' + k + ' nhóm (chưa áp dụng)';
        renderDistribution(state.clusters.reduce((sum, item) => sum + Number(item.count), 0));
        renderProfiles();
    }
    function renderDistribution(total) {
        state.pieChart?.destroy();
        state.pieChart = null;
        $('distributionLegend').replaceChildren();
        state.clusters.forEach((item) => {
            const row = element('div', 'distribution-item');
            const dot = element('span', 'color-dot'); dot.style.backgroundColor = color(item.color);
            const name = element('span', 'distribution-name', item.name); name.title = item.name;
            row.append(dot, name, element('strong', 'distribution-value', count(total ? Number(item.count) / total * 100 : 0) + '%'));
            $('distributionLegend').append(row);
        });
        if (!total) { sectionState('distributionState', 'Chưa có khách hàng trong kết quả phân tích.'); return; }
        if (typeof window.Chart === 'undefined') { sectionState('distributionState', 'Biểu đồ chưa tải được. Xem số liệu trong bảng bên dưới.'); return; }
        const centerText = {
            id: 'customerTotal',
            afterDraw(chart) {
                const area = chart.chartArea;
                if (!area) return;
                const x = (area.left + area.right) / 2, y = (area.top + area.bottom) / 2;
                const ctx = chart.ctx;
                ctx.save();
                ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
                ctx.fillStyle = '#364d37'; ctx.font = '600 26px Segoe UI, sans-serif';
                ctx.fillText(count(total), x, y - 6);
                ctx.fillStyle = '#68785e'; ctx.font = '12px Segoe UI, sans-serif';
                ctx.fillText('khách hàng', x, y + 17);
                ctx.restore();
            }
        };
        state.pieChart = new Chart($('distributionChart'), {
            type: 'doughnut',
            data: { labels: state.clusters.map((item) => item.name), datasets: [{ data: state.clusters.map((item) => Number(item.count)), backgroundColor: state.clusters.map((item) => color(item.color)), borderColor: '#ffffff', borderWidth: 4, hoverOffset: 3, borderRadius: 3 }] },
            options: { responsive: true, maintainAspectRatio: false, cutout: '77%', animation: false, plugins: { legend: { display: false }, tooltip: { callbacks: { label: (ctx) => ' ' + count(ctx.raw) + ' khách hàng · ' + count(Number(ctx.raw) / total * 100) + '%' } } } },
            plugins: [centerText]
        });
        $('distributionState').hidden = true;
    }
    function behaviorCell(item) {
        return element('td', 'behavior-cell', item.behavior_desc || '—');
    }
    function renderProfiles() {
        if (!state.clustersReady) return;
        const query = normalized($('clusterSearch').value);
        const filtered = state.clusters.filter((item) => normalized(item.name + ' ' + item.behavior_desc).includes(query));
        const total = state.clusters.reduce((sum, item) => sum + Number(item.count), 0);
        $('clusterProfileTableBody').replaceChildren();
        if (!filtered.length) tableState('clusterProfileTableBody', 8, query ? 'Không tìm thấy phân khúc phù hợp.' : 'Chưa có dữ liệu phân khúc.');
        filtered.forEach((item) => {
            const row = element('tr');
            const name = element('td'), segment = element('div', 'segment-cell'), dot = element('span', 'color-dot');
            dot.style.backgroundColor = color(item.color);
            segment.append(dot, element('strong', '', item.name));
            name.append(segment);
            const percentage = element('td', 'numeric');
            percentage.append(element('span', 'percentage-pill', count(total ? Number(item.count) / total * 100 : 0) + '%'));
            row.append(name, element('td', 'numeric', count(item.count)), percentage, element('td', 'numeric', count(item.current_count)), element('td', 'numeric amount-positive', money(item.monetary_spent)), element('td', 'numeric', numeric(item.frequency_orders) ? countFormat.format(Math.round(Number(item.frequency_orders) * 10) / 10) : '—'), element('td', 'numeric', numeric(item.recency_days) ? count(Math.round(Number(item.recency_days))) : '—'), behaviorCell(item));
            $('clusterProfileTableBody').append(row);
        });
        $('profilesSummary').textContent = count(filtered.length) + ' / ' + count(state.clusters.length) + ' phân khúc · ' + count(total) + ' khách lúc học';
    }

    async function loadStrategies(selectedId = $('stratSelect').value) {
        try {
            const data = await request(API + '/strategies');
            if (!Array.isArray(data) || !data.every((item) => Number.isInteger(item.segmentId))) throw new Error('Dữ liệu chiến lược không hợp lệ.');
            state.strategies = data.slice().sort((a, b) => Number(a.segmentRank ?? a.segmentId) - Number(b.segmentRank ?? b.segmentId));
            state.strategyStale = !state.activeSynchronized || data.length !== state.activeK ||
                data.some((item) => item.activeVersion !== state.activeVersion);
            $('stratSelect').replaceChildren();
            state.strategies.forEach((item) => $('stratSelect').add(new Option(strategyName(item), String(item.segmentId))));
            if (state.strategies.some((item) => String(item.segmentId) === String(selectedId))) $('stratSelect').value = String(selectedId);
            $('strategyCount').textContent = count(data.length);
            $('strategyFields').disabled = !data.length || state.strategyStale || state.mutationBusy;
            $('saveStrategyButton').disabled = !data.length || state.strategyStale || state.mutationBusy;
            loadStrategyDetail();
            if (state.activeSynchronized && state.strategyStale) {
                $('strategySyncNote').textContent = 'Danh sách ưu đãi chưa khớp phân cụm đang dùng. Hãy làm mới để đọc lại SQL.';
                $('strategySyncNote').classList.add('error');
            }
            return state.strategies;
        } catch (error) {
            state.strategies = null;
            $('strategyCount').textContent = '—';
            $('strategyList').replaceChildren(element('p', 'empty-state error', error.message));
            $('strategyFields').disabled = true;
            $('saveStrategyButton').disabled = true;
            $('strategyForm').reset();
            $('stratSelect').replaceChildren();
            $('strategyUpdated').textContent = 'Chưa đọc được chiến lược từ SQL Server.';
            $('strategyActive').textContent = '—';
            return null;
        }
    }
    function loadStrategyDetail() {
        state.strategyDirty = false;
        const selected = state.strategies?.find((item) => String(item.segmentId) === $('stratSelect').value);
        $('strategyList').replaceChildren();
        if (!state.strategies?.length) {
            $('strategyList').append(element('p', 'empty-state', 'Chưa có chiến lược nào được lưu trong cơ sở dữ liệu.'));
            $('strategyForm').reset();
            $('strategyUpdated').textContent = 'Chưa có chiến lược để hiển thị.';
            $('strategyActive').textContent = '—';
            return;
        }
        const inactive = state.strategies.filter((item) => item.isActive !== true);
        if (inactive.length) {
            $('strategyList').append(element('p', 'strategy-warning', inactive.length === state.strategies.length
                ? 'Tất cả ' + count(inactive.length) + ' ưu đãi đang tắt: quầy POS chưa giảm giá hay tặng quà cho khách nào. Chọn từng ưu đãi, kiểm tra rồi bật "Bật ưu đãi cho quầy POS".'
                : count(inactive.length) + '/' + count(state.strategies.length) + ' ưu đãi đang tắt: khách thuộc các nhóm này chưa nhận ưu đãi tại quầy.'));
        }
        state.strategies.forEach((item) => {
            const button = element('button', 'strategy-item' + (selected === item ? ' active' : '') + (item.isActive === true ? '' : ' is-off'));
            button.type = 'button';
            const info = element('span');
            const subtitle = strategyName(item) + (numeric(item.monetarySpent) ? ' · Chi tiêu TB ' + money(item.monetarySpent) : '');
            info.append(element('span', 'strategy-item-title', item.title || 'Ưu đãi · ' + strategyName(item)), element('span', 'strategy-item-subtitle', subtitle));
            const side = element('span', 'strategy-item-side');
            side.append(element('span', 'strategy-item-discount', numeric(item.discountPercent) ? count(item.discountPercent) + '%' : '—'),
                element('span', 'strategy-status ' + (item.isActive === true ? 'on' : 'off'), item.isActive === true ? 'Đang bật' : 'Đang tắt'));
            button.append(info, side);
            button.addEventListener('click', () => { if (!state.mutationBusy) { $('stratSelect').value = String(item.segmentId); loadStrategyDetail(); } });
            $('strategyList').append(button);
        });
        if (!selected) return;
        $('stratTitle').value = selected.title ?? '';
        $('stratDiscount').value = selected.discountPercent ?? '';
        $('stratGift').value = selected.giftDescription ?? '';
        $('stratScript').value = selected.cashierScript ?? '';
        $('stratEnabled').checked = selected.isActive === true;
        $('strategyUpdated').textContent = selected.updatedAt ? 'Cập nhật: ' + date(selected.updatedAt) : 'Chưa có thông tin thời gian cập nhật.';
        $('strategyActive').textContent = selected.isActive === true ? 'Đang bật' : (selected.isActive === false ? 'Đang tắt' : 'Chưa có trạng thái');
    }
    async function saveStrategy(event) {
        event.preventDefault();
        if (state.mutationBusy || !state.strategies?.length || !$('strategyForm').reportValidity()) return;
        const id = Number($('stratSelect').value);
        const payload = { title: $('stratTitle').value.trim(), discountPercent: Number($('stratDiscount').value), giftDescription: $('stratGift').value, cashierScript: $('stratScript').value, isActive: $('stratEnabled').checked };
        if (!payload.title) { toast('Vui lòng nhập tên chiến lược.', true); return; }
        const selected = state.strategies.find((item) => item.segmentId === id);
        await persistStrategy(id, payload, { activeVersion: selected.activeVersion, expectedStrategyId: selected.id, expectedUpdatedAt: selected.updatedAt });
    }
    async function persistStrategy(id, payload, snapshot) {
        if (state.mutationBusy) return;
        if (state.strategyStale || !state.activeSynchronized || !snapshot?.activeVersion || snapshot.activeVersion !== state.activeVersion) {
            toast('Phân cụm chưa đồng bộ hoặc đã thay đổi. Hãy làm mới dữ liệu trước khi lưu ưu đãi.', true); return;
        }
        if (!Number.isInteger(id) || !payload.title || payload.title.length > 150 || (payload.giftDescription || '').length > 255 ||
            !numeric(payload.discountPercent) || Number(payload.discountPercent) < 0 || Number(payload.discountPercent) > 100) {
            toast('Đề xuất chưa đủ thông tin hợp lệ để lưu. Kiểm tra tên, nhóm và mức giảm giá.', true); return;
        }
        const name = strategyName(state.strategies?.find((item) => item.segmentId === id));
        const accepted = await confirmAction('Lưu ưu đãi cho ' + name + '?', payload.title + '\nMức giảm giá: ' + count(payload.discountPercent) + '%.\nThông tin sẽ được cập nhật vào cơ sở dữ liệu và sử dụng cho quầy POS.', 'Xác nhận lưu');
        if (!accepted || state.mutationBusy) return;
        state.mutationBusy = true;
        $('saveStrategyButton').disabled = true;
        $('strategyFields').disabled = true;
        try {
            await request(API + '/strategies/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...payload, ...snapshot }) });
            await loadStrategies(id);
            toast('Đã lưu ưu đãi cho ' + name + '.');
        } catch (error) { toast('Chưa lưu được chiến lược. ' + error.message, true); }
        finally {
            state.mutationBusy = false;
            $('saveStrategyButton').disabled = !state.strategies?.length || state.strategyStale;
            $('strategyFields').disabled = !state.strategies?.length || state.strategyStale;
        }
    }
    function paymentLabel(method) {
        return ({ CASH: 'Tiền mặt', CARD: 'Thẻ', BANK_TRANSFER: 'Chuyển khoản', TRANSFER: 'Chuyển khoản', QR: 'Chuyển khoản', ONLINE: 'Trực tuyến' })[method] || method || '—';
    }
    function orderQuery(extra = {}) {
        const query = new URLSearchParams({ search: $('orderSearch').value.trim(), ...extra });
        if ($('paymentFilter').value) query.set('payment', $('paymentFilter').value);
        if ($('orderFrom').value) query.set('from', $('orderFrom').value);
        if ($('orderTo').value) query.set('to', $('orderTo').value);
        return query;
    }
    async function loadOrders() {
        const sequence = ++state.orderRequest;
        tableState('ordersTableBody', 7, 'Đang tải hóa đơn…');
        try {
            const data = await request(API + '/orders?' + orderQuery({ page: state.page - 1, size: state.pageSize }));
            if (sequence !== state.orderRequest) return;
            const pages = Math.max(1, Math.ceil(data.total / data.size));
            if (state.page > pages) { state.page = pages; return loadOrders(); }
            $('ordersTableBody').replaceChildren();
            if (!data.items.length) tableState('ordersTableBody', 7, 'Không tìm thấy hóa đơn phù hợp với bộ lọc.');
            data.items.forEach((order) => {
                const row = element('tr'), idCell = element('td'), paymentCell = element('td');
                const detailButton = element('button', 'order-detail-link', order.transactionNo || '—');
                detailButton.type = 'button';
                detailButton.addEventListener('click', () => document.dispatchEvent(new CustomEvent('admin-order', { detail: order.transactionNo })));
                idCell.append(detailButton);
                paymentCell.append(element('span', 'payment-badge', paymentLabel(order.paymentMethod)));
                const customer = order.customerCode ? order.customerCode + (order.customerName ? ' · ' + order.customerName : '') : 'Vãng lai';
                row.append(idCell, element('td', '', date(order.orderDate)), element('td', '', customer), element('td', 'numeric', money(order.totalAmount)), element('td', 'numeric amount-discount', money(order.discountAmount)), element('td', 'numeric amount-positive', money(order.finalAmount)), paymentCell);
                $('ordersTableBody').append(row);
            });
            const offset = (state.page - 1) * data.size;
            $('orderResultCount').textContent = count(data.total) + ' hóa đơn · ' + money(data.revenue);
            $('ordersPaginationInfo').textContent = data.total ? 'Hiển thị ' + count(offset + 1) + '–' + count(Math.min(offset + data.size, data.total)) + ' / ' + count(data.total) + ' hóa đơn' : 'Không có bản ghi';
            $('pageNumber').textContent = state.page + ' / ' + count(pages);
            $('previousPage').disabled = state.page <= 1;
            $('nextPage').disabled = state.page >= pages;
            $('exportOrdersButton').disabled = !data.total;
        } catch (error) {
            if (sequence === state.orderRequest) tableState('ordersTableBody', 7, error.message, true);
        }
    }
    let orderTimer;
    function reloadOrders(delay = 0) { clearTimeout(orderTimer); state.page = 1; orderTimer = setTimeout(loadOrders, delay); }
    function exportOrders() {
        const anchor = element('a');
        anchor.href = API + '/orders/export?' + orderQuery();
        anchor.download = 'hoa-don-' + new Date().toLocaleDateString('sv-SE') + '.csv';
        document.body.append(anchor); anchor.click(); anchor.remove();
    }

    function toggleChat(open = $('chatDrawer').hidden) {
        $('chatDrawer').hidden = !open;
        $('chatToggle').setAttribute('aria-expanded', String(open));
        if (open) $('chatInput').focus(); else $('chatToggle').focus();
    }
    function chatMessage(text, user = false, error = false) {
        const message = element('div', 'chat-msg ' + (user ? 'user' : 'ai'));
        const bubble = element('div', 'chat-bubble' + (error ? ' error' : ''), text);
        message.append(element('span', 'chat-avatar', user ? 'Bạn' : 'AI'), bubble);
        $('chatWindow').append(message);
        $('chatWindow').scrollTop = $('chatWindow').scrollHeight;
        return { message, bubble };
    }
    function renderProposal(proposal, parent, context) {
        const card = element('div', 'proposal-card');
        card.append(element('span', 'proposal-caption', 'ĐỀ XUẤT AI · CHƯA LƯU'), element('h3', '', proposal.campaign_title || 'Đề xuất chiến lược'));
        card.append(element('p', '', segmentLabel(proposal.segment_name || 'Phân khúc khách hàng')));
        card.append(element('p', '', 'Mức giảm: ' + count(proposal.discount_percent) + '%'));
        card.append(element('p', '', 'Quà tặng: ' + (proposal.gift_description || 'Chưa đề xuất')));
        if (proposal.cashier_script) card.append(element('p', '', 'Tư vấn: ' + proposal.cashier_script));
        if (proposal.rationale) card.append(element('p', '', proposal.rationale));
        const save = element('button', 'button button-secondary', 'Xem lại và lưu đề xuất');
        save.type = 'button';
        const target = state.strategies?.find((item) => item.segmentId === proposal.segment_id);
        const applicable = context.k === state.activeK && context.activeVersion &&
            context.activeVersion === state.activeVersion && state.activeSynchronized && !state.strategyStale && target?.activeVersion === context.activeVersion;
        const snapshot = target ? { activeVersion: context.activeVersion, expectedStrategyId: target.id, expectedUpdatedAt: target.updatedAt } : null;
        if (!applicable) {
            save.disabled = true;
            save.textContent = 'Chỉ lưu được cho mô hình đang áp dụng';
            card.append(element('p', 'assistant-notice', 'Đề xuất này dựa trên cách chia khác với mô hình đang áp dụng. Hãy hỏi lại theo số nhóm đang dùng để lưu được.'));
        }
        save.addEventListener('click', () => persistStrategy(proposal.segment_id, { title: proposal.campaign_title, discountPercent: proposal.discount_percent, giftDescription: proposal.gift_description, cashierScript: proposal.cashier_script }, snapshot));
        card.append(save); parent.append(card);
    }
    function renderAssistantReply(parent, reply) {
        const content = element('div', 'assistant-response');
        let list = null;
        const appendInline = (target, value) => {
            value.split(/(\*\*[^*]+\*\*)/g).forEach((part) => {
                if (part.startsWith('**') && part.endsWith('**')) target.append(element('strong', '', part.slice(2, -2)));
                else target.append(document.createTextNode(part));
            });
        };
        reply.split('\n').forEach((line) => {
            if (!line.trim()) { list = null; return; }
            const ordered = /^\s*\d+[.)]\s+/.test(line);
            if (ordered || /^\s*[-*•]\s+/.test(line)) {
                const tag = ordered ? 'OL' : 'UL';
                if (!list || list.tagName !== tag) { list = element(tag.toLowerCase()); content.append(list); }
                const item = element('li'); appendInline(item, line.replace(/^\s*(?:[-*•]|\d+[.)])\s+/, '')); list.append(item);
            } else {
                list = null;
                const paragraph = element(/^#{1,4}\s+/.test(line) ? 'h3' : 'p');
                appendInline(paragraph, line.replace(/^#{1,4}\s+/, '')); content.append(paragraph);
            }
        });
        parent.replaceChildren(content);
    }
    async function sendChat(event) {
        event?.preventDefault();
        const input = $('chatInput'), message = input.value.trim();
        if (!message || state.chatBusy) return;
        if (!state.clustersReady || !state.selectedK) { toast('Cần kết nối dữ liệu phân cụm trước khi hỏi trợ lý.', true); return; }
        state.chatBusy = true; $('sendChatButton').disabled = true;
        const k = state.selectedK;
        const activeVersion = state.activeVersion;
        input.value = '';
        chatMessage(message, true);
        const loading = chatMessage('Đang phân tích dữ liệu khách hàng…');
        try {
            const data = await request(AI + '/chat-strategy', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message, k, history: state.chatHistory.slice(-12) }), signal: AbortSignal.timeout(110000) });
            if (typeof data.reply !== 'string') throw new Error('Trợ lý chưa trả về nội dung hợp lệ.');
            loading.message.remove();
            const answer = chatMessage(data.reply);
            renderAssistantReply(answer.bubble, data.reply);
            answer.message.querySelector('.chat-avatar').textContent = data.source === 'data' ? 'DL' : 'AI';
            answer.bubble.append(element('div', 'assistant-meta', data.source === 'gemini' ? 'Phân tích bằng ' + data.model : 'Tóm tắt dữ liệu cửa hàng'));
            if (data.notice) answer.bubble.append(element('p', 'assistant-notice', data.notice));
            state.chatHistory.push({ role: 'user', content: message }, { role: 'assistant', content: data.reply.slice(0, 6000) });
            state.chatHistory = state.chatHistory.slice(-12);
            if (Number.isInteger(data.suggested_k) && data.suggested_k !== state.selectedK && state.metrics?.k_range.includes(data.suggested_k)) {
                const view = element('button', 'button button-secondary suggested-k-button', 'Xem trước ' + data.suggested_k + ' nhóm ở trang Mô hình');
                view.type = 'button';
                view.addEventListener('click', () => { if (!state.mutationBusy) { switchTab('model'); document.dispatchEvent(new CustomEvent('admin-model-k', { detail: data.suggested_k })); } });
                answer.bubble.append(view);
            }
            if (Array.isArray(data.proposals)) data.proposals.forEach((proposal) => renderProposal(proposal, answer.bubble,
                { k: Number.isInteger(data.analysis_k) ? data.analysis_k : null,
                    activeVersion: data.active_version === activeVersion ? activeVersion : null }));
            $('chatWindow').scrollTop = $('chatWindow').scrollHeight;
        } catch (error) { loading.message.remove(); chatMessage('Chưa nhận được phản hồi từ trợ lý. ' + error.message, false, true); }
        finally { state.chatBusy = false; $('sendChatButton').disabled = false; }
    }

    async function refresh() {
        if (state.refreshBusy || state.mutationBusy) return;
        state.refreshBusy = true;
        $('refreshButton').disabled = true;
        $('refreshButton').classList.add('is-loading');
        try {
            await Promise.allSettled([loadStore(), loadAnalysis()]);
            await loadStrategies();
            document.dispatchEvent(new Event('admin-refresh'));
        }
        finally {
            state.refreshBusy = false;
            $('refreshButton').disabled = false;
            $('refreshButton').classList.remove('is-loading');
        }
    }

    $('today').textContent = new Intl.DateTimeFormat('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date());
    document.querySelectorAll('.nav-item').forEach((button) => button.addEventListener('click', () => switchTab(button.dataset.tab)));
    $('refreshButton').addEventListener('click', refresh);
    $('clusterSearch').addEventListener('input', renderProfiles);
    $('stratSelect').addEventListener('change', loadStrategyDetail);
    $('strategyForm').addEventListener('submit', saveStrategy);
    $('strategyForm').addEventListener('input', () => { state.strategyDirty = true; });
    $('openModelTab').addEventListener('click', () => switchTab('model'));
    $('applyAnalysisK').addEventListener('click', () => {
        const k = state.viewK;
        if (!Number.isInteger(k) || state.mutationBusy) return;
        switchTab('model');
        document.dispatchEvent(new CustomEvent('admin-model-k', { detail: k }));
    });
    document.addEventListener('admin-clusters-applied', async () => {
        state.chatHistory = [];
        await loadAnalysis();
        await loadStrategies('');
    });
    $('orderSearch').addEventListener('input', () => reloadOrders(300));
    $('paymentFilter').addEventListener('change', () => reloadOrders());
    $('previousPage').addEventListener('click', () => { state.page--; loadOrders(); });
    $('nextPage').addEventListener('click', () => { state.page++; loadOrders(); });
    $('exportOrdersButton').addEventListener('click', exportOrders);
    $('chatToggle').addEventListener('click', () => toggleChat());
    $('chatClose').addEventListener('click', () => toggleChat(false));
    $('chatForm').addEventListener('submit', sendChat);
    document.querySelectorAll('[data-prompt]').forEach((button) => button.addEventListener('click', () => {
        if (state.chatBusy) return;
        $('chatInput').value = button.dataset.prompt;
        $('chatForm').requestSubmit();
    }));
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && !$('chatDrawer').hidden && !$('confirmDialog').open) toggleChat(false); });
    ['orderFrom', 'orderTo'].forEach(id => $(id).addEventListener('change', () => reloadOrders()));
    window.addEventListener('storage', async (event) => {
        if (event.key !== 'cosmetic-crm.model-update' || !event.newValue || state.mutationBusy || state.refreshBusy) return;
        let update;
        try { update = JSON.parse(event.newValue); } catch { return; }
        if (!update.version || update.version === state.activeVersion) return;
        if ($('tab-strategies').hidden) { await refreshStrategyMetadata(); return; }
        state.strategyStale = true;
        $('strategyFields').disabled = true;
        $('saveStrategyButton').disabled = true;
        $('strategySyncNote').textContent = 'Phân cụm đã thay đổi ở tab khác. Nội dung đang xem được giữ lại; bấm Làm mới để tải danh sách mới trước khi lưu.';
        $('strategySyncNote').classList.add('error');
        toast('Phân cụm đã được cập nhật ở tab khác. Hãy làm mới danh sách ưu đãi trước khi tiếp tục.', true);
    });
    refresh();
})();
