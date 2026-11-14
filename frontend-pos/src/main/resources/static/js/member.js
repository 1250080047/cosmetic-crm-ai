'use strict';
// Trang thẻ thành viên: đăng nhập / đăng ký / kích hoạt, rồi hiện thẻ có mã QR đổi mỗi 60 giây, điểm và lịch sử mua.
(() => {
    const $ = (id) => document.getElementById(id);
    const money = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const number = new Intl.NumberFormat('vi-VN');
    const day = (value) => value ? new Date(value).toLocaleDateString('vi-VN') : '';
    const moment = (value) => value ? new Date(value).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) : '';
    const state = { card: null, qrTimer: 0, tickTimer: 0, busy: false };

    function el(tag, text, className) {
        const node = document.createElement(tag);
        if (text !== undefined && text !== null) node.textContent = String(text);
        if (className) node.className = className;
        return node;
    }
    async function api(path, body) {
        const headers = { 'Accept': 'application/json' };
        const options = { headers, credentials: 'same-origin', signal: AbortSignal.timeout(20000) };
        if (body !== undefined) {
            options.method = 'POST';
            headers['Content-Type'] = 'application/json';
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            if (token && header) headers[header] = token;
            options.body = JSON.stringify(body);
        }
        const response = await fetch(path, options);
        let data = null;
        try { data = await response.json(); } catch { /* handled below */ }
        if (!response.ok || data?.success === false) {
            const error = new Error(data?.message || 'Chưa kết nối được cửa hàng. Kiểm tra mạng rồi thử lại.');
            error.status = response.status;
            throw error;
        }
        return data;
    }

    // ---- views --------------------------------------------------------------------------------
    function showPageState(text, error = false) {
        $('pageState').hidden = !text;
        $('pageState').textContent = text || '';
        $('pageState').classList.toggle('error', error);
    }
    function showAuth(tab = 'login', message = '') {
        stopQr();
        state.card = null;
        showPageState('');
        $('cardView').hidden = true;
        $('authView').hidden = false;
        selectTab(tab);
        authMessage(message);
    }
    function selectTab(tab, focus = false) {
        document.querySelectorAll('.tab').forEach((button) => button.setAttribute('aria-selected', String(button.dataset.tab === tab)));
        document.querySelectorAll('.auth-form').forEach((form) => { form.hidden = form.dataset.form !== tab; });
        authMessage('');
        if (focus) document.querySelector('.auth-form[data-form="' + tab + '"] input')?.focus({ preventScroll: true });
    }
    function authMessage(text, ok = false) {
        $('authMessage').hidden = !text;
        $('authMessage').textContent = text || '';
        $('authMessage').classList.toggle('ok', ok);
    }
    function showCard(card) {
        state.card = card;
        showPageState('');
        $('authView').hidden = true;
        $('cardView').hidden = false;
        $('cardName').textContent = card.name;
        $('cardCode').textContent = card.customer_code;
        $('cardPhone').textContent = card.phone || '';
        $('cardSince').textContent = card.member_since ? 'Thành viên từ ' + day(card.member_since) : '';
        $('pointsValue').textContent = number.format(card.points || 0);
        $('pointsWorth').textContent = Number(card.points_value) > 0 ? '≈ ' + money.format(Number(card.points_value)) + ' khi đổi' : '';
        $('pointsRule').textContent = card.points_rule || '';
        $('redeemRule').textContent = card.redeem_rule || '';
        renderTier(card);
        renderOffer(card.offer, card.total_orders);
        renderHistory(card);
        loadQr();
        loadReviews();
    }

    // ---- reviews of products bought at the store ------------------------------------------------
    async function loadReviews() {
        const list = $('reviewList');
        try {
            const data = await api('/api/member/reviews');
            $('reviewHint').textContent = 'Viết nhận xét từ ' + data.bonus_min_chars + ' ký tự cho lần đánh giá đầu tiên của mỗi sản phẩm để được thưởng ' +
                data.bonus_points + ' điểm.';
            renderReviews(data.products || []);
        } catch (error) {
            if (error.status === 401) { showAuth('login', 'Phiên đăng nhập đã hết hạn. Hãy nhập lại PIN.'); return; }
            list.replaceChildren(el('li', 'Chưa tải được danh sách: ' + error.message, 'empty'));
        }
    }
    const stars = (rating) => '★'.repeat(rating) + '☆'.repeat(5 - rating);
    function renderReviews(products) {
        const list = $('reviewList');
        list.replaceChildren();
        if (!products.length) { list.append(el('li', 'Mua sắm tại cửa hàng để đánh giá sản phẩm bạn đã dùng.', 'empty')); return; }
        products.forEach((product) => {
            const li = el('li', null, 'review-item'), head = el('div', null, 'review-head');
            const title = el('div');
            title.append(el('span', product.productName), el('small', 'Mua ngày ' + day(product.lastBought)));
            const button = el('button', product.reviewId ? 'Sửa' : 'Đánh giá', 'secondary review-open');
            button.type = 'button';
            head.append(title, button);
            li.append(head);
            if (product.reviewId) {
                const mine = el('div', null, 'review-mine');
                mine.append(el('span', stars(product.rating), 'review-stars'));
                if (product.comment) mine.append(el('p', product.comment));
                if (product.status === 'HIDDEN') mine.append(el('small', 'Cửa hàng đang xem lại đánh giá này.', 'muted-note'));
                li.append(mine);
            }
            button.addEventListener('click', () => openReviewForm(li, product));
            list.append(li);
        });
    }
    function openReviewForm(li, product) {
        li.querySelector('.review-form')?.remove();
        const form = el('form', null, 'review-form');
        let rating = product.rating || 0;
        const row = el('div', null, 'star-row');
        row.setAttribute('role', 'radiogroup'); row.setAttribute('aria-label', 'Số sao');
        const paint = () => row.querySelectorAll('button').forEach((star, i) => {
            star.classList.toggle('on', i < rating); star.setAttribute('aria-checked', String(i + 1 === rating));
        });
        for (let i = 1; i <= 5; i++) {
            const star = el('button', '★', 'star');
            star.type = 'button'; star.setAttribute('role', 'radio'); star.setAttribute('aria-label', i + ' sao');
            star.addEventListener('click', () => { rating = i; paint(); });
            row.append(star);
        }
        const text = el('textarea');
        text.maxLength = 500; text.rows = 3; text.placeholder = 'Mùi hương, cảm giác trên da, hiệu quả, bao bì…';
        text.value = product.comment || '';
        const counter = el('small', text.value.length + '/500', 'counter');
        text.addEventListener('input', () => { counter.textContent = text.value.length + '/500'; });
        const message = el('p', null, 'form-message'); message.hidden = true;
        const save = el('button', 'Gửi đánh giá', 'primary'); save.type = 'submit';
        const cancel = el('button', 'Hủy', 'secondary'); cancel.type = 'button';
        cancel.addEventListener('click', () => form.remove());
        const actions = el('div', null, 'review-actions'); actions.append(cancel, save);
        form.append(row, text, counter, message, actions);
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            if (!rating) { message.hidden = false; message.textContent = 'Chọn số sao.'; return; }
            save.disabled = true;
            try {
                const result = await api('/api/member/reviews', { productId: product.productId, rating, comment: text.value });
                message.hidden = false; message.classList.add('ok'); message.textContent = result.message;
                setTimeout(async () => { try { showCard(await api('/api/member/me')); } catch { loadReviews(); } }, 1200);
            } catch (error) {
                message.hidden = false; message.classList.remove('ok'); message.textContent = error.message; save.disabled = false;
            }
        });
        li.append(form);
        paint();
        text.focus({ preventScroll: true });
    }
    // Tier from the store spend of the last 12 months: Thành viên → Bạc (2.000.000 đ, points ×1,5) → Vàng (5.000.000 đ, ×2).
    function renderTier(card) {
        const label = card.tier_label || 'Thành viên';
        $('cardTier').textContent = label === 'Thành viên' ? '' : '· ' + label.toUpperCase();
        document.querySelector('.member-card').dataset.tier = card.tier || 'MEMBER';
        $('tierNow').textContent = 'Hạng ' + label;
        const spend = Number(card.store_spend_12m) || 0, next = Number(card.next_tier_spend) || 0;
        $('tierNext').textContent = card.next_tier_label ? 'Hạng ' + card.next_tier_label : 'Hạng cao nhất';
        $('tierBar').style.transform = 'scaleX(' + (next ? Math.min(1, spend / next) : 1) + ')';
        $('tierNote').textContent = card.next_tier_label
            ? 'Đã chi ' + money.format(spend) + ' tại cửa hàng trong 12 tháng · còn ' + money.format(Number(card.to_next_tier) || 0) +
              ' nữa lên hạng ' + card.next_tier_label + (card.next_tier === 'GOLD' ? ' (điểm ×2)' : ' (điểm ×1,5)')
            : 'Bạn đang ở hạng cao nhất: điểm ×2 cho mỗi hóa đơn.';
    }
    function renderOffer(offer, totalOrders) {
        const body = $('offerBody');
        body.replaceChildren();
        if (!offer) {
            body.append(el('p', totalOrders ? 'Hiện chưa có ưu đãi mới cho bạn. Cửa hàng sẽ cập nhật sau mỗi lần bạn mua sắm.'
                : 'Mua đơn đầu tiên tại cửa hàng để nhận ưu đãi dành riêng cho bạn.', 'empty'));
            return;
        }
        body.append(el('div', offer.title, 'offer-title'));
        if (Number(offer.discountPercent) > 0) body.append(el('p', 'Giảm ' + number.format(offer.discountPercent) + '% khi thanh toán tại quầy.', 'offer-line'));
        if (offer.gift) body.append(el('p', 'Quà tặng: ' + offer.gift, 'offer-line'));
        body.append(el('p', 'Ưu đãi được áp dụng khi thu ngân quét thẻ của bạn.', 'empty'));
    }
    function renderHistory(card) {
        const orders = $('orderList'), points = $('pointList');
        orders.replaceChildren(); points.replaceChildren();
        if (!card.orders?.length) orders.append(el('li', 'Chưa có hóa đơn nào.', 'empty'));
        (card.orders || []).forEach((order) => {
            const li = el('li'), left = el('div'), right = el('div', null, 'amount');
            left.append(el('span', moment(order.orderDate)), el('small', number.format(order.items || 0) + ' sản phẩm' + (order.paymentMethod === 'ONLINE' ? ' · mua online' : '')));
            right.append(el('span', money.format(Number(order.paid || 0))));
            if (Number(order.pointsUsed) > 0) right.append(el('small', '−' + number.format(order.pointsUsed) + ' điểm đã dùng', 'minus'));
            if (Number(order.points) > 0) right.append(el('small', '+' + number.format(order.points) + ' điểm', 'plus'));
            li.append(left, right); orders.append(li);
        });
        if (!card.points_history?.length) points.append(el('li', 'Chưa có điểm. Mỗi 10.000 đ thanh toán tại quầy được 1 điểm.', 'empty'));
        (card.points_history || []).forEach((row) => {
            const li = el('li'), left = el('div');
            left.append(el('span', row.note), el('small', moment(row.at)));
            li.append(left, el('div', (row.points > 0 ? '+' : '') + number.format(row.points), 'amount ' + (row.points < 0 ? 'minus' : 'plus')));
            points.append(li);
        });
    }

    // ---- QR code that changes every 60 seconds ------------------------------------------------
    function stopQr() {
        clearTimeout(state.qrTimer); clearInterval(state.tickTimer);
        state.qrTimer = 0; state.tickTimer = 0;
    }
    async function loadQr() {
        stopQr();
        if (!state.card) return;
        try {
            const data = await api('/api/member/qr');
            const box = $('qrBox');
            box.replaceChildren(window.QrSvg.render(data.token, { alphanumeric: true }));
            box.setAttribute('aria-label', 'Mã QR thẻ thành viên ' + state.card.customer_code);
            $('qrNote').classList.remove('error');
            countdown(data.seconds_left, data.step_seconds);
            state.qrTimer = setTimeout(loadQr, Math.max(1, data.seconds_left) * 1000);
        } catch (error) {
            if (error.status === 401) { showAuth('login', 'Phiên đăng nhập đã hết hạn. Hãy nhập lại PIN.'); return; }
            $('qrNote').textContent = 'Chưa tạo được mã QR: ' + error.message + ' Đang thử lại…';
            $('qrNote').classList.add('error');
            state.qrTimer = setTimeout(loadQr, 5000);
        }
    }
    function countdown(secondsLeft, step) {
        let left = secondsLeft;
        const paint = () => {
            $('qrTimerBar').style.transform = 'scaleX(' + Math.max(0, left / step) + ')';
            $('qrNote').textContent = 'Mã tự đổi sau ' + Math.max(0, left) + ' giây · Thu ngân quét mã này để áp dụng ưu đãi và cộng điểm.';
        };
        paint();
        state.tickTimer = setInterval(() => { left -= 1; paint(); }, 1000);
    }

    // ---- forms ----------------------------------------------------------------------------------
    async function submit(event) {
        event.preventDefault();
        if (state.busy) return;
        const form = event.target, kind = form.dataset.form, values = Object.fromEntries(new FormData(form).entries());
        Object.keys(values).forEach((key) => { values[key] = String(values[key]).trim(); });
        if (!values.phone) return authMessage('Nhập số điện thoại.');
        if (!/^\d{6}$/.test(values.pin || '')) return authMessage('PIN gồm đúng 6 chữ số.');
        if ('pin2' in values && values.pin !== values.pin2) return authMessage('Hai lần nhập PIN chưa giống nhau.');
        if (kind === 'register' && (values.fullName || '').length < 2) return authMessage('Nhập họ và tên.');
        if (kind === 'activate' && !/^\d{6}$/.test(values.code || '')) return authMessage('Mã kích hoạt gồm 6 chữ số thu ngân đọc cho bạn.');
        const button = form.querySelector('button[type="submit"]');
        state.busy = true; button.disabled = true;
        try {
            const body = kind === 'register' ? { fullName: values.fullName, phone: values.phone, pin: values.pin }
                : kind === 'activate' ? { phone: values.phone, code: values.code, pin: values.pin }
                : { phone: values.phone, pin: values.pin };
            const result = await api('/api/member/' + kind, body);
            form.reset();
            showCard(result.card);
        } catch (error) {
            authMessage(error.message);
        } finally { state.busy = false; button.disabled = false; }
    }
    async function logout() {
        try { await api('/api/member/logout', {}); } catch { /* the page is cleared anyway */ }
        showAuth('login', 'Đã đăng xuất.');
        $('authMessage').classList.add('ok');
    }
    async function start() {
        try { showCard(await api('/api/member/me')); }
        catch (error) {
            if (error.status === 401) showAuth('login');
            else showPageState('Chưa mở được thẻ. ' + error.message, true);
        }
    }

    document.querySelectorAll('.tab').forEach((button) => button.addEventListener('click', () => selectTab(button.dataset.tab, true)));
    document.querySelectorAll('.auth-form').forEach((form) => form.addEventListener('submit', submit));
    $('logoutButton').addEventListener('click', logout);
    // A phone that was asleep comes back with an old code: make a fresh one straight away.
    document.addEventListener('visibilitychange', () => { if (!document.hidden && state.card) loadQr(); else if (document.hidden) stopQr(); });
    start();
})();
