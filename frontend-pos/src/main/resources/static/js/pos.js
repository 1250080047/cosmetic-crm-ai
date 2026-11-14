'use strict';

(() => {
    const $ = (id) => document.getElementById(id);
    const state = {
        products: [], categories: [], category: 'ALL', cart: [], customer: null, discount: 0,
        busy: false, lookingUp: false, shift: null, shiftKnown: false, report: null
    };
    const format = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const money = (value) => format.format(Number(value) || 0);
    const round = (value) => Math.round((value + Number.EPSILON) * 100) / 100;
    const text = (value) => String(value ?? '');
    const normalize = (value) => text(value).normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').toLowerCase();
    const node = (tag, className, value) => {
        const result = document.createElement(tag);
        if (className) result.className = className;
        if (value != null) result.textContent = text(value);
        return result;
    };
    async function request(url, options = {}) {
        const response = await fetch(url, { ...options, signal: options.signal || AbortSignal.timeout(25000) });
        let data;
        try { data = await response.json(); } catch { throw new Error('Phản hồi dịch vụ không hợp lệ.'); }
        if (!response.ok || data.success === false) throw new Error(data.message || 'Dịch vụ trả về lỗi ' + response.status + '.');
        return data;
    }
    const post = (url, body) => request(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    const giftOptions = () => state.customer?.offer_available ? (state.customer.gift_options || []) : [];
    const giftLimit = () => Number(state.customer?.gift_quantity_limit) || 1;
    const giftUnits = () => state.cart.filter((item) => item.gift).reduce((sum, item) => sum + item.qty, 0);
    const inCart = (id) => state.cart.filter((item) => item.id === id).reduce((sum, item) => sum + item.qty, 0);
    const eligibleGift = (id) => giftOptions().some((item) => item.product_id === id);
    const remainingGift = () => Math.max(0, giftLimit() - giftUnits());
    const lineKey = (id, gift) => id + (gift ? ':gift' : ':sale');
    const categoryName = (id) => state.categories.find((category) => category.id === id)?.name || 'Chưa phân loại';
    const CATEGORY_ART = Object.fromEntries(['Chăm sóc da', 'Chống nắng', 'Chăm sóc tóc', 'Trang điểm', 'Chăm sóc móng',
        'Dụng cụ làm đẹp', 'Phụ kiện & thiết bị tóc', 'Nước hoa & khử mùi', 'Tắm & chăm sóc cơ thể',
        'Cạo râu & tẩy lông', 'Làm đẹp tổng hợp'].map((name, index) => [name, '/images/categories/' + (index + 1) + '.svg']));

    function renderProductImage(imageBox, product) {
        const placeholder = '/images/products/placeholder.svg';
        const fallback = CATEGORY_ART[categoryName(product.categoryId)] || placeholder;
        const local = typeof product.imageUrl === 'string' && /^\/images\/(products|categories)\/[\w.-]+\.(jpg|svg)$/.test(product.imageUrl);
        const image = node('img', 'product-img');
        const caption = node('span', 'image-caption', 'Ảnh minh họa');
        image.loading = 'lazy'; image.decoding = 'async';
        const show = (src) => {
            const illustration = src.startsWith('/images/categories/');
            const photo = src.endsWith('.jpg');
            imageBox.dataset.imageKind = photo ? 'photo' : illustration ? 'illustration' : 'placeholder';
            image.alt = photo ? product.productName : illustration ? categoryName(product.categoryId) + ' · Ảnh minh họa' : 'Chưa có ảnh sản phẩm';
            if (illustration) imageBox.append(caption); else caption.remove();
            if (photo) imageBox.title = 'Ảnh: Open Beauty Facts (CC BY-SA 3.0)'; else imageBox.removeAttribute('title');
            image.src = src;
        };
        // Try category artwork and then the neutral placeholder if an image is missing.
        image.addEventListener('error', () => {
            const src = image.getAttribute('src');
            if (src !== fallback && src !== placeholder) show(fallback);
            else if (src !== placeholder) show(placeholder);
            else {
                image.remove(); caption.remove(); imageBox.dataset.imageKind = 'missing';
                imageBox.append(node('span', 'product-image-unavailable', 'Chưa có hình ảnh'));
            }
        });
        imageBox.append(image);
        show(local ? product.imageUrl : fallback);
    }

    function totals() {
        const subtotal = round(state.cart.reduce((sum, item) => sum + item.price * item.qty, 0));
        const percentDiscount = round(subtotal * state.discount / 100);
        return { subtotal, percentDiscount, finalAmount: round(Math.max(0, subtotal - percentDiscount)) };
    }

    // ---- Catalog -------------------------------------------------------------------------------
    async function loadCategories() {
        try {
            state.categories = await request('/api/pos/categories');
            const bar = $('categoryBar');
            bar.replaceChildren();
            const all = node('button', 'cat-btn' + (state.category === 'ALL' ? ' active' : ''), '✨ Tất cả');
            all.type = 'button'; all.dataset.category = 'ALL'; bar.append(all);
            state.categories.forEach((category) => {
                const button = node('button', 'cat-btn' + (String(state.category) === String(category.id) ? ' active' : ''), category.name);
                button.type = 'button'; button.dataset.category = String(category.id);
                bar.append(button);
            });
        } catch { state.categories = []; }
    }
    async function loadProducts() {
        try {
            const products = await request('/api/pos/products');
            if (!Array.isArray(products)) throw new Error('Danh mục sản phẩm không hợp lệ.');
            state.products = products;
            renderProductGrid();
            renderOffer();
            return true;
        } catch (error) {
            $('productList').replaceChildren(node('p', 'catalog-state', 'Chưa tải được danh mục. ' + error.message));
            return false;
        }
    }
    function renderProductGrid() {
        const search = normalize($('searchInput').value.trim());
        const products = state.products.filter((product) => Boolean(product.isActive) &&
            (state.category === 'ALL' || String(product.categoryId) === String(state.category)) &&
            (normalize(product.productName).includes(search) || normalize(product.barcode).includes(search)));
        const grid = $('productList');
        grid.replaceChildren();
        if (!products.length) { grid.append(node('p', 'catalog-state', 'Không tìm thấy sản phẩm phù hợp.')); return; }
        products.forEach((product) => {
            const card = node('article', 'product-card');
            card.dataset.productId = product.id;
            card.tabIndex = 0;
            card.setAttribute('aria-label', product.productName);
            // Images are local files only (no external links): a verified product photo or the group illustration.
            const imageBox = node('div', 'product-img-wrapper');
            renderProductImage(imageBox, product);
            imageBox.append(node('span', 'stock-tag', 'Kho: ' + product.stockQuantity));
            const info = node('div', 'product-info');
            const title = node('div', 'product-title', product.productName);
            title.title = product.productName;
            info.append(title, node('span', 'product-category-tag', categoryName(product.categoryId)),
                node('div', 'product-price', money(product.unitPrice)), node('div', 'product-barcode', 'Mã: ' + (product.barcode || product.id)));
            const actions = node('div', 'product-actions');
            const gift = eligibleGift(product.id);
            if (gift) info.append(node('span', 'gift-eligible-badge', 'Quà trong ưu đãi · 0đ'));
            const add = node('button', 'product-add-button', gift ? 'Thêm quà 0đ' : 'Thêm vào hóa đơn');
            add.type = 'button';
            add.disabled = product.stockQuantity <= inCart(product.id) || (gift && remainingGift() <= 0) || state.busy;
            add.addEventListener('click', (event) => { event.stopPropagation(); addToCart(product, gift ? 'gift' : 'sale'); });
            actions.append(add);
            if (gift) {
                const buy = node('button', 'product-buy-button', 'Mua thêm');
                buy.type = 'button';
                buy.disabled = product.stockQuantity <= inCart(product.id) || state.busy;
                buy.addEventListener('click', (event) => { event.stopPropagation(); addToCart(product, 'sale'); });
                actions.append(buy);
            }
            card.addEventListener('click', () => addToCart(product));
            card.addEventListener('keydown', (event) => {
                if (event.target === card && ['Enter', ' '].includes(event.key)) { event.preventDefault(); addToCart(product); }
            });
            info.append(actions); card.append(imageBox, info); grid.append(card);
        });
    }

    // ---- Cart ----------------------------------------------------------------------------------
    function addToCart(product, mode = 'auto') {
        if (state.busy) return;
        const gift = mode === 'gift' || (mode === 'auto' && eligibleGift(product.id));
        if (gift && (!eligibleGift(product.id) || remainingGift() <= 0)) {
            alert('Đã chọn đủ suất quà. Nếu khách mua thêm, chọn “Mua thêm” để tính giá bán.'); return;
        }
        if (product.stockQuantity <= inCart(product.id)) { alert('Số lượng đã chọn đạt tồn kho của sản phẩm.'); return; }
        const key = lineKey(product.id, gift), existing = state.cart.find((item) => item.key === key);
        if (existing) existing.qty++;
        else state.cart.push({ key, id: product.id, name: product.productName, price: gift ? 0 : Number(product.unitPrice), retailPrice: Number(product.unitPrice), gift, qty: 1 });
        renderCart(); renderOffer(); renderProductGrid();
    }
    function changeQty(key, delta) {
        if (state.busy) return;
        const item = state.cart.find((line) => line.key === key);
        if (!item) return;
        const product = state.products.find((entry) => entry.id === item.id);
        if (delta > 0 && (inCart(item.id) >= (product?.stockQuantity || 0) || (item.gift && remainingGift() <= 0))) {
            alert(item.gift ? 'Đã đủ suất quà của ưu đãi.' : 'Số lượng đã chọn đạt tồn kho.'); return;
        }
        item.qty += delta;
        if (item.qty <= 0) state.cart = state.cart.filter((line) => line !== item);
        renderCart(); renderOffer(); renderProductGrid();
    }
    function renderCart() {
        const body = $('cartTableBody');
        body.replaceChildren();
        if (!state.cart.length) {
            const row = node('tr'), cell = node('td', 'cart-empty', 'Chọn sản phẩm để bắt đầu hóa đơn.');
            cell.colSpan = 5; row.append(cell); body.append(row);
        }
        state.cart.forEach((item) => {
            const row = node('tr', item.gift ? 'gift-line' : '');
            row.dataset.lineKey = item.key;
            const name = node('td'), label = node('div', 'cart-item-name', item.name);
            name.append(label);
            if (item.gift) name.append(node('span', 'gift-line-badge', 'Quà tặng · 0đ'));
            const quantity = node('td'), controls = node('div', 'qty-control');
            const minus = node('button', 'qty-btn', '−'), plus = node('button', 'qty-btn', '+');
            minus.type = plus.type = 'button';
            minus.setAttribute('aria-label', 'Giảm số lượng ' + item.name);
            plus.setAttribute('aria-label', 'Tăng số lượng ' + item.name);
            minus.disabled = state.busy;
            plus.disabled = state.busy || (item.gift && remainingGift() <= 0) || inCart(item.id) >= (state.products.find((p) => p.id === item.id)?.stockQuantity || 0);
            minus.addEventListener('click', () => changeQty(item.key, -1)); plus.addEventListener('click', () => changeQty(item.key, 1));
            controls.append(minus, node('strong', '', item.qty), plus); quantity.append(controls);
            const price = node('td', 'cart-unit-price', money(item.price));
            if (item.gift) price.append(node('del', 'retail-reference', money(item.retailPrice)));
            const removeCell = node('td'), remove = node('button', 'del-btn', '×');
            remove.type = 'button'; remove.disabled = state.busy; remove.setAttribute('aria-label', 'Bỏ ' + item.name);
            remove.addEventListener('click', () => { state.cart = state.cart.filter((line) => line.key !== item.key); renderCart(); renderOffer(); renderProductGrid(); });
            removeCell.append(remove);
            row.append(name, quantity, price, node('td', 'cart-line-total', money(item.price * item.qty)), removeCell); body.append(row);
        });
        const amounts = totals();
        $('cartCountBadge').textContent = '(' + state.cart.reduce((sum, item) => sum + item.qty, 0) + ' món' + (giftUnits() ? ' · ' + giftUnits() + ' quà' : '') + ')';
        $('subTotal').textContent = money(amounts.subtotal);
        $('discountAmount').textContent = '−' + money(amounts.percentDiscount);
        $('finalTotal').textContent = money(amounts.finalAmount);
        $('discountRateText').textContent = state.discount + '%';
        const button = document.querySelector('.btn-checkout');
        const noShift = state.shiftKnown && !state.shift;
        button.disabled = state.busy || state.lookingUp || !state.cart.length || noShift;
        button.textContent = state.busy ? 'Đang lưu hóa đơn…' : (noShift ? 'Mở ca để thanh toán' : '⚡ THANH TOÁN & TRỪ KHO');
        $('shiftWarning').hidden = !noShift;
    }
    function clearCart() {
        if (state.busy) return;
        state.cart = [];
        renderCart(); renderOffer(); renderProductGrid();
    }

    // ---- Customer & offer ----------------------------------------------------------------------
    function showCustAlert(message, registerPhone = null) {
        const box = $('custInlineAlert');
        box.replaceChildren(node('div', '', message));
        if (registerPhone !== null) {
            const register = node('button', 'btn-inline-reg', '+ Đăng ký thành viên mới');
            register.type = 'button'; register.addEventListener('click', () => openNewCustomerModal(registerPhone)); box.append(register);
        }
        box.style.display = 'block';
    }
    function hideCustAlert() { $('custInlineAlert').style.display = 'none'; }
    function renderOffer() {
        const box = $('aiContent');
        box.replaceChildren();
        if (!state.customer) { box.append(node('p', '', 'Tra cứu khách hàng để xem ưu đãi đã được lưu trong hệ thống.')); return; }
        const offer = state.customer;
        box.append(node('p', 'offer-discount', offer.offer_available ? 'Ưu đãi hiện có: giảm ' + state.discount + '%' : 'Nhóm khách này chưa có ưu đãi đang bật.'));
        if (!offer.ai_available) box.append(node('p', 'offer-warning', 'Chưa kết nối được AI. Không tự gán ưu đãi dự phòng.'));
        if (offer.gift) box.append(node('p', 'offer-gift', 'Quà theo chương trình: ' + offer.gift));
        box.append(node('p', 'cashier-caption', 'Lời tư vấn dành cho khách'), node('blockquote', 'script-text', offer.script || 'Xin chào anh/chị. Em có thể hỗ trợ anh/chị tìm sản phẩm phù hợp không ạ?'));
        const options = giftOptions();
        if (options.length) {
            const selection = node('div', 'gift-picker'), label = node('label', '', 'Chọn sản phẩm tặng thực tế');
            const select = node('select');
            select.id = 'giftProductSelect';
            label.htmlFor = select.id;
            options.forEach((gift) => select.add(new Option(gift.name, String(gift.product_id))));
            const add = node('button', 'btn-add-gift', 'Thêm quà tặng · 0đ');
            add.id = 'addGiftButton'; add.type = 'button'; add.disabled = remainingGift() <= 0 || state.busy || !state.products.length;
            add.addEventListener('click', () => {
                const product = state.products.find((entry) => entry.id === Number(select.value));
                if (product) addToCart(product, 'gift');
            });
            selection.append(label, select, node('p', 'gift-limit-note', 'Đã chọn ' + giftUnits() + '/' + giftLimit() + ' sản phẩm tặng. Phần mua thêm tính giá bán.'), add);
            box.append(selection);
        } else if (offer.gift) {
            box.append(node('p', 'gift-unlinked-note', /voucher|tích điểm|hoàn tiền/i.test(offer.gift)
                ? 'Ưu đãi này không phải hàng tặng trong kho; không tự thêm sản phẩm giá 0đ.'
                : 'Chưa tìm thấy sản phẩm đang bán, còn kho khớp mô tả quà. Cần kiểm tra lại chương trình; không tự thay bằng hàng khác.'));
        }
    }
    async function lookupCustomer() {
        if (state.lookingUp || state.busy) return;
        const key = $('phoneInput').value.trim();
        if (!key) { showCustAlert('Nhập số điện thoại hoặc mã khách hàng (VD: KH00012).'); return; }
        state.lookingUp = true;
        hideCustAlert();
        resetCustomer(false);
        try {
            const customer = await request('/api/pos/customer-lookup?q=' + encodeURIComponent(key));
            if (!customer.found) {
                if (customer.reason === 'qr') { $('phoneInput').value = ''; showCustAlert(customer.message); return; }
                showCustAlert('Không tìm thấy khách hàng.', /^\+?\d{8,15}$/.test(key) ? key : null);
                return;
            }
            if (customer.scanned) $('phoneInput').value = customer.customer_code;   // the long QR code is not useful to read
            applyCustomerToPos(customer);
        } catch (error) { showCustAlert('Chưa tra cứu được khách hàng. ' + error.message); }
        finally { state.lookingUp = false; renderCart(); }
    }
    function applyCustomerToPos(customer) {
        state.customer = customer;
        state.discount = customer.offer_available && Number.isFinite(Number(customer.discount_percent)) ? Number(customer.discount_percent) : 0;
        state.cart = state.cart.filter((item) => !item.gift);
        $('customerInfo').style.display = 'block';
        $('custName').textContent = customer.name || 'Thành viên';
        $('custCode').textContent = customer.customer_code || '—';
        $('custPhone').textContent = customer.phone || 'chưa có';
        $('custHistory').textContent = customer.has_purchase
            ? 'Lần cuối cách đây ' + customer.recency_days + ' ngày · 12 tháng qua: ' + customer.orders_12m + ' đơn, ' + money(customer.spent_12m) +
              ' · tổng ' + customer.total_orders + ' đơn từ trước tới nay'
            : 'Chưa mua hàng: nhóm khách được xác định sau hóa đơn đầu tiên';
        $('custSegment').textContent = customer.segment || 'Chưa xác định';
        $('custSegment').style.background = /^#[a-f0-9]{6}$/i.test(customer.color || '') ? customer.color : '#64748b';
        $('custPoints').textContent = new Intl.NumberFormat('vi-VN').format(customer.points || 0);
        const active = customer.member_status === 'ACTIVE';
        $('custMemberStatus').textContent = active ? '· Đã có thẻ thành viên' : (customer.member_status === 'PENDING' ? '· Đang chờ kích hoạt thẻ' : '· Chưa có thẻ thành viên');
        $('activationButton').textContent = active ? '🔑 Cấp mã đặt lại PIN' : '🔑 Cấp mã kích hoạt thẻ';
        renderCart(); renderOffer(); renderProductGrid();
    }
    function resetCustomer(clearPhone = true) {
        state.customer = null; state.discount = 0;
        state.cart = state.cart.filter((item) => !item.gift);
        $('customerInfo').style.display = 'none';
        if (clearPhone) $('phoneInput').value = '';
        hideCustAlert(); renderCart(); renderOffer(); renderProductGrid();
    }
    function removeCustomer() { if (!state.busy) resetCustomer(); }
    function openNewCustomerModal(phone = '') {
        if (state.busy) return;
        const typed = $('phoneInput').value.trim();
        $('newCustPhone').value = phone || (/^\+?\d{8,15}$/.test(typed) ? typed : ''); $('newCustName').value = '';
        $('newCustomerModal').style.display = 'flex'; $('newCustName').focus();
    }
    function closeNewCustomerModal() { $('newCustomerModal').style.display = 'none'; }
    async function saveNewCustomer() {
        const phone = $('newCustPhone').value.trim(), fullName = $('newCustName').value.trim();
        if (!phone || !fullName) { alert('Vui lòng nhập số điện thoại và họ tên.'); return; }
        const button = $('newCustomerModal').querySelector('button:last-child');
        button.disabled = true;
        try {
            const result = await post('/api/pos/customers', { phone, fullName });
            if (!result.success) throw new Error('Chưa xác nhận tạo thành viên.');
            closeNewCustomerModal(); $('phoneInput').value = phone;
            await lookupCustomer();
        } catch (error) { alert('Chưa đăng ký được thành viên. ' + error.message); }
        finally { button.disabled = false; }
    }

    // ---- Member card: QR to open the member page, one-time activation code ----------------------
    const MEMBER_SCOPE = {
        internet: '🌐 Khách dùng 4G hay Wi-Fi nào cũng mở được (qua đường hầm Cloudflare).',
        wifi: '📶 Chỉ điện thoại dùng chung Wi-Fi với máy này mới mở được. Muốn khách dùng mạng nào cũng mở được: chạy scripts\\public-member.cmd rồi mở lại hộp này.'
    };
    async function openMemberModal(issued = null) {
        $('activationBox').hidden = !issued;
        if (issued) {
            $('activationName').textContent = state.customer ? state.customer.name + ' (' + state.customer.customer_code + ')' : '';
            $('activationCode').textContent = issued.code.replace(/^(\d{3})(\d{3})$/, '$1 $2');
            $('activationNote').textContent = 'Hiệu lực ' + issued.minutes + ' phút, dùng một lần. Khách vào trang thẻ → "Kích hoạt thẻ" → nhập số điện thoại '
                + (state.customer?.phone || '') + ', mã này và PIN mới.' + (issued.resets_pin ? ' PIN cũ sẽ được thay.' : '');
        }
        $('memberModal').style.display = 'flex';
        try {
            const memberLink = await request('/api/pos/member-link');   // asked every time: the tunnel may have started meanwhile
            $('memberQr').replaceChildren(window.QrSvg.render(memberLink.url));
            $('memberUrl').textContent = memberLink.url;
            $('memberScope').textContent = MEMBER_SCOPE[memberLink.scope] || '';
            $('memberWarning').hidden = memberLink.reachable_from_phones;
            $('memberWarning').textContent = memberLink.reachable_from_phones ? '' :
                'Máy tính chưa kết nối Wi-Fi/mạng nội bộ nên điện thoại chưa mở được địa chỉ này. Hãy cho máy tính và điện thoại dùng chung một Wi-Fi, hoặc chạy scripts\\public-member.cmd.';
            if (memberLink.alternatives?.length) $('memberUrl').textContent += ' (cùng Wi-Fi: ' + memberLink.alternatives.join(', ') + ')';
        } catch (error) {
            $('memberQr').replaceChildren(node('p', 'member-warning', 'Chưa tạo được mã QR: ' + error.message));
        }
    }
    function closeMemberModal() { $('memberModal').style.display = 'none'; }
    async function issueActivationCode() {
        if (!state.customer || state.busy) return;
        const button = $('activationButton');
        button.disabled = true;
        try {
            const issued = await post('/api/pos/customers/' + state.customer.customer_id + '/activation-code', {});
            await openMemberModal(issued);
        } catch (error) { alert('Chưa cấp được mã kích hoạt. ' + error.message); }
        finally { button.disabled = false; }
    }

    // ---- Checkout ------------------------------------------------------------------------------
    async function handleCheckout() {
        if (state.busy || state.lookingUp || !state.cart.length) return;
        if (!state.shift) { openShiftDialog(); return; }
        const amounts = totals();
        if (giftUnits() && amounts.subtotal <= 0) { alert('Quà tặng cần đi cùng sản phẩm mua trong hóa đơn.'); return; }
        const payload = {
            customerId: state.customer?.customer_id ?? null,
            totalAmount: amounts.subtotal, discountAmount: amounts.percentDiscount,
            finalAmount: amounts.finalAmount, paymentMethod: $('paymentMethod').value,
            items: state.cart.map((item) => ({ productId: item.id, quantity: item.qty, unitPrice: item.price, gift: item.gift }))
        };
        state.busy = true; renderCart(); renderOffer(); renderProductGrid();
        try {
            const result = await post('/api/pos/checkout', payload);
            if (!result.success || !Array.isArray(result.items)) throw new Error('Chưa nhận được hóa đơn đã xác nhận từ máy chủ.');
            showReceipt(result);
            await Promise.all([loadProducts(), loadShift()]);
        } catch (error) {
            alert('Chưa xác nhận được thanh toán. ' + error.message);
            if (/mở ca/i.test(error.message)) await loadShift();
        }
        finally { state.busy = false; renderCart(); renderOffer(); renderProductGrid(); }
    }
    function showReceipt(result) {
        $('rcpTransNo').textContent = 'Mã HD: ' + result.transaction_no;
        $('rcpDate').textContent = 'Ngày: ' + result.order_date + (window.storeUser ? ' · Thu ngân: ' + window.storeUser.fullName : '');
        $('rcpCustName').textContent = state.customer ? (state.customer.name + ' (' + state.customer.customer_code + ')') : 'Khách vãng lai';
        $('rcpSegment').textContent = Number(result.segment_discount_amount) > 0 ? 'Đã áp dụng ưu đãi của cửa hàng' : 'Theo hóa đơn thực tế';
        $('rcpItems').replaceChildren();
        result.items.forEach((item) => {
            const row = node('div', 'receipt-row' + (item.gift ? ' receipt-gift' : ''));
            row.append(node('span', '', item.product_name + ' ×' + item.quantity + (item.gift ? ' · Quà tặng' : '')), node('span', '', money(item.line_total)));
            $('rcpItems').append(row);
        });
        $('rcpSubtotal').textContent = money(result.total_amount);
        $('rcpDiscount').textContent = '−' + money(result.segment_discount_amount);
        $('rcpFinal').textContent = money(result.final_amount);
        const hasPoints = result.points_balance !== null && result.points_balance !== undefined;
        $('rcpPointsRow').style.display = hasPoints ? 'flex' : 'none';
        $('rcpPoints').textContent = hasPoints ? '+' + Number(result.points_earned || 0).toLocaleString('vi-VN') + ' (tổng ' + Number(result.points_balance).toLocaleString('vi-VN') + ' điểm)' : '';
        $('receiptModal').style.display = 'flex';
    }
    function closeReceipt() {
        $('receiptModal').style.display = 'none';
        state.cart = []; resetCustomer(); renderCart();
    }

    // ---- Shift ---------------------------------------------------------------------------------
    function renderShift() {
        const status = $('shiftStatus'), open = Boolean(state.shift);
        status.className = 'shift-status ' + (open ? 'open' : 'closed');
        status.textContent = open
            ? 'Ca #' + state.shift.shift.id + ' · từ ' + new Date(state.shift.shift.openedAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) +
              ' · ' + state.shift.summary.orders + ' HĐ · ' + money(state.shift.summary.revenue)
            : 'Chưa mở ca';
        $('openShiftButton').hidden = open;
        $('shiftReportButton').hidden = !open;
        $('closeShiftButton').hidden = !open;
        renderCart();
    }
    async function loadShift() {
        try {
            const data = await request('/api/pos/shift');
            state.shift = data.open || null;
            state.shiftKnown = true;
        } catch (error) {
            $('shiftStatus').textContent = 'Chưa đọc được ca: ' + error.message;
            return;
        }
        renderShift();
    }
    function openShiftDialog() { $('openingCash').value = '0'; $('openShiftModal').style.display = 'flex'; $('openingCash').focus(); }
    async function confirmOpenShift() {
        const button = $('confirmOpenShift');
        button.disabled = true;
        try {
            state.shift = await post('/api/pos/shift/open', { openingCash: Number($('openingCash').value) || 0 });
            state.shiftKnown = true;
            $('openShiftModal').style.display = 'none';
            renderShift();
        } catch (error) { alert('Chưa mở được ca. ' + error.message); await loadShift(); }
        finally { button.disabled = false; }
    }
    async function showShiftReport(closing) {
        await loadShift();
        if (!state.shift) { alert('Không có ca đang mở.'); return; }
        state.report = state.shift;
        $('shiftReportTitle').textContent = closing ? 'Kết ca — kiểm tra trước khi xác nhận' : 'Báo cáo ca đang mở';
        ShiftReport.render($('shiftReportBody'), state.report);
        $('shiftCloseForm').hidden = !closing;
        $('countedCash').value = closing ? String(Math.round(Number(state.report.cash.expected) || 0)) : '';
        $('shiftNote').value = '';
        $('shiftReportModal').style.display = 'flex';
    }
    async function confirmCloseShift() {
        const counted = $('countedCash').value === '' ? null : Number($('countedCash').value);
        if (!confirm('Kết thúc ca? Sau khi kết ca bạn cần mở ca mới để bán tiếp.')) return;
        const button = $('confirmCloseShift');
        button.disabled = true;
        try {
            state.report = await post('/api/pos/shift/close', { countedCash: counted, note: $('shiftNote').value });
            state.shift = null;
            $('shiftReportTitle').textContent = 'Báo cáo kết ca #' + state.report.shift.id;
            ShiftReport.render($('shiftReportBody'), state.report);
            $('shiftCloseForm').hidden = true;
            renderShift();
        } catch (error) { alert('Chưa kết ca được. ' + error.message); }
        finally { button.disabled = false; }
    }

    // ---- Wiring --------------------------------------------------------------------------------
    $('categoryBar').addEventListener('click', (event) => {
        const button = event.target.closest('.cat-btn');
        if (!button) return;
        state.category = button.dataset.category;
        document.querySelectorAll('.cat-btn').forEach((entry) => entry.classList.toggle('active', entry === button));
        renderProductGrid();
    });
    $('memberLinkButton').addEventListener('click', () => openMemberModal());
    $('activationButton').addEventListener('click', issueActivationCode);
    $('memberClose').addEventListener('click', closeMemberModal);
    $('memberModal').addEventListener('click', (event) => { if (event.target === $('memberModal')) closeMemberModal(); });
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && $('memberModal').style.display === 'flex') closeMemberModal(); });
    $('openShiftButton').addEventListener('click', openShiftDialog);
    $('cancelOpenShift').addEventListener('click', () => { $('openShiftModal').style.display = 'none'; });
    $('confirmOpenShift').addEventListener('click', confirmOpenShift);
    $('shiftReportButton').addEventListener('click', () => showShiftReport(false));
    $('closeShiftButton').addEventListener('click', () => showShiftReport(true));
    $('confirmCloseShift').addEventListener('click', confirmCloseShift);
    $('closeShiftReport').addEventListener('click', () => { $('shiftReportModal').style.display = 'none'; });
    $('exportShiftRevenue').addEventListener('click', () => state.report && ShiftReport.exportRevenue(state.report));
    $('exportShiftGifts').addEventListener('click', () => state.report && ShiftReport.exportGifts(state.report));
    $('printShiftReport').addEventListener('click', () => window.print());
    Object.assign(window, {
        handleSearch: renderProductGrid,
        clearCart, renderCart, lookupCustomer, removeCustomer, hideCustAlert,
        openNewCustomerModal, closeNewCustomerModal, saveNewCustomer, handleCheckout, closeReceipt
    });
    (async () => {
        await loadCategories();
        await Promise.all([loadProducts(), loadShift()]);
        renderCart();
        if (state.shiftKnown && !state.shift) openShiftDialog();
    })();
})();
