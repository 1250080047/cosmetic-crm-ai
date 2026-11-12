'use strict';
(() => {
    const $ = id => document.getElementById(id);
    const state = { report: null, chart: null, products: [], categories: [], users: [], customerPage: 0, customerRequest: 0, reportRequest: 0, productPage: 0, productRequest: 0, shiftReport: null, tab: 'overview', loaded: new Set() };
    const currency = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
    const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
    const money = value => value == null ? '—' : currency.format(value);
    const count = value => value == null ? '—' : number.format(value);
    const date = value => value ? new Date(value).toLocaleString('vi-VN') : 'Chưa ghi nhận';
    const normalized = value => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').toLowerCase();
    const roles = { ADMIN: 'Quản trị viên', MANAGER: 'Quản lý', CASHIER: 'Thu ngân' };
    const payments = { CASH: 'Tiền mặt', CARD: 'Thẻ', BANK_TRANSFER: 'Chuyển khoản', TRANSFER: 'Chuyển khoản', QR: 'Chuyển khoản', ONLINE: 'Trực tuyến' };
    function el(tag, text, className) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
    function notify(message, error = false) { const node = el('div', message, 'toast' + (error ? ' error' : '')); $('toastRegion').append(node); setTimeout(() => node.remove(), 7000); }
    async function api(path, options = {}) {
        const response = await fetch('/api/admin' + path, { ...options, signal: options.signal || AbortSignal.timeout(45000) });
        const data = await response.json();
        if (!response.ok || data?.success === false) throw new Error(data.message || 'Chưa thực hiện được yêu cầu (' + response.status + ').');
        return data;
    }
    function table(id, rows, columns, empty = 'Chưa có dữ liệu trong kỳ này.') {
        const body = $(id); body.replaceChildren();
        if (!rows.length) { const tr = el('tr'), td = el('td', empty, 'table-state'); td.colSpan = columns.length; tr.append(td); body.append(tr); }
        rows.forEach(item => {
            const row = el('tr');
            columns.forEach(column => {
                const td = el('td', null, column.numeric ? 'numeric' : '');
                const value = column.value(item);
                if (value instanceof Node) td.append(value); else td.textContent = value ?? '—';
                row.append(td);
            }); body.append(row);
        });
    }
    function tableError(id, columns, message) { const tr=el('tr'), td=el('td',message,'table-state error'); td.colSpan=columns; tr.append(td); $(id).replaceChildren(tr); }
    function stat(label, value, note) {
        const article = el('article', null, 'stat-card'); article.append(el('div', label, 'stat-top'), el('strong', value, 'stat-value'), el('p', note, 'stat-note')); return article;
    }
    function openDialog(title) { $('workspaceDialogTitle').textContent=title; $('workspaceDialogBody').replaceChildren(); if (!$('workspaceDialog').open) $('workspaceDialog').showModal(); return $('workspaceDialogBody'); }
    $('closeWorkspaceDialog').addEventListener('click', () => $('workspaceDialog').close());
    function field(form, label, name, value = '', type = 'text', attrs = {}) {
        const wrapper = el('label', label), input = el('input'); input.name=name; input.type=type; input.value=value ?? '';
        Object.entries(attrs).forEach(([key, val]) => { if (val === true) input.setAttribute(key, ''); else if (val !== false) input.setAttribute(key,val); });
        wrapper.append(input); form.append(wrapper); return input;
    }
    function select(form, label, name, entries, value) {
        const wrapper = el('label',label), input=el('select'); input.name=name;
        entries.forEach(([key,text]) => input.add(new Option(text,key))); input.value=String(value ?? ''); wrapper.append(input); form.append(wrapper); return input;
    }
    function checkbox(form,label,name,checked) { const wrapper=el('label',null,'checkbox-label'), input=el('input'); input.type='checkbox';input.name=name;input.checked=checked; wrapper.append(input,document.createTextNode(label));form.append(wrapper);return input; }
    function saveForm(body, onSave, text = 'Lưu thay đổi') {
        const form=el('form'), error=el('p',null,'form-error'); error.setAttribute('role','alert');
        const submit=el('button',text,'button button-primary'); submit.type='submit';
        form.addEventListener('submit',async event => {
            event.preventDefault(); if(!form.reportValidity())return; submit.disabled=true; error.textContent='';
            try { await onSave(form); $('workspaceDialog').close(); notify('Đã lưu thay đổi.'); }
            catch(ex) { error.textContent=ex.message; } finally { submit.disabled=false; }
        }); body.append(form); return { form, finish: () => form.append(error,submit) };
    }
    function writeOptions(method, data) { return { method, headers:{'Content-Type':'application/json'}, body:JSON.stringify(data) }; }
    function download(name, rows) {
        const cell=value => { let text=String(value ?? ''); if(/^[=+@-]/.test(text)&&!/^-?\d+(\.\d+)?$/.test(text))text="'"+text; return '"'+text.replace(/"/g,'""')+'"'; };
        const url=URL.createObjectURL(new Blob(['\ufeff'+rows.map(row=>row.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}));
        const anchor=el('a');anchor.href=url;anchor.download=name;anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    }

    for(let m=1;m<=12;m++)$('revenueMonth').add(new Option('Tháng '+m,m));
    async function initRevenue() {
        try {
            const data=await api('/revenue/options'); const previous=$('revenueYear').value;
            $('revenueYear').replaceChildren(); data.years.forEach(year=>$('revenueYear').add(new Option(year,year)));
            $('revenueYear').value=previous && data.years.includes(Number(previous)) ? previous : String(data.lastYear || data.currentYear);
            const last=data.lastOrder ? new Date(data.lastOrder) : new Date();
            if(!state.loaded.has('revenue-options'))$('revenueMonth').value=String(last.getMonth()+1);
            state.loaded.add('revenue-options'); $('revenueStatus').textContent='Chọn kỳ để xem so sánh. Năm mặc định theo hóa đơn mới nhất.';
            await loadRevenue();
        } catch(error) { $('revenueStatus').textContent=error.message; $('revenueStatus').classList.add('error'); }
    }
    async function loadRevenue() {
        if(!$('revenueYear').value)return;
        const sequence=++state.reportRequest;
        $('runRevenue').disabled=true;$('exportRevenue').disabled=true;$('revenueResults').hidden=true;state.report=null;
        $('revenueStatus').textContent='Đang tính báo cáo từ SQL Server…';$('revenueStatus').classList.remove('error');
        const query=new URLSearchParams({granularity:$('revenueGranularity').value,year:$('revenueYear').value,month:$('revenueMonth').value,comparison:$('revenueComparison').value});
        try {
            const data=await api('/revenue?'+query); if(sequence!==state.reportRequest)return;
            state.report=data;$('revenueResults').hidden=false;renderRevenue(data);$('exportRevenue').disabled=false;
            $('revenueStatus').textContent='Cập nhật: '+date(data.generatedAt)+' · Múi giờ Việt Nam';
        }catch(error){if(sequence===state.reportRequest){$('revenueStatus').textContent=error.message;$('revenueStatus').classList.add('error');}}
        finally{if(sequence===state.reportRequest)$('runRevenue').disabled=false;}
    }
    function renderRevenue(data) {
        const a=data.current,b=data.previous,c=data.comparison,w=data.window;
        $('revenueWindow').textContent='Kỳ chọn: '+date(w.start)+' → '+date(w.end)+' · Đối chiếu: '+date(w.previousStart)+' → '+date(w.previousEnd)+' (không gồm mốc kết thúc)';
        const growth=c.revenueChangePercent==null?'Chưa có doanh thu gốc để tính %':(c.revenueChangePercent>0?'+':'')+count(c.revenueChangePercent)+'%';
        $('revenueKpis').replaceChildren(stat('Doanh thu sau giảm giá',money(a.revenue),'Kỳ đối chiếu: '+money(b.revenue)+' · '+growth),stat('Hóa đơn ghi nhận',count(a.orders),'Kỳ đối chiếu: '+count(b.orders)),stat('Giá trị đơn trung bình',money(a.average),'Kỳ đối chiếu: '+money(b.average)),stat('Giảm giá trên tạm tính',count(a.discountRate)+'%','Giảm '+money(a.discount)+' · Kỳ gốc: '+count(b.discountRate)+'%'));
        $('revenueChartCaption').textContent=data.year+' và '+(data.year-1)+' · Hai năm so đến cùng mốc lịch nếu năm đang diễn ra. Bộ lọc tháng áp dụng cho các chỉ số và bảng phân tích phía dưới.';
        state.chart?.destroy();state.chart=null;
        $('revenueChartFallback').hidden=typeof Chart!=='undefined';$('revenueChart').hidden=typeof Chart==='undefined';
        if(typeof Chart!=='undefined')state.chart=new Chart($('revenueChart'),{type:'bar',data:{labels:data.trend.map(row=>'T'+row.month),datasets:[{label:String(data.year),data:data.trend.map(row=>row.future?null:row.current),backgroundColor:'#367b60',borderRadius:3},{label:String(data.year-1),data:data.trend.map(row=>row.future?null:row.previous),backgroundColor:'#c4b59c',borderRadius:3}]},options:{responsive:true,maintainAspectRatio:false,animation:false,interaction:{mode:'index',intersect:false},plugins:{tooltip:{callbacks:{label:item=>item.dataset.label+': '+money(item.parsed.y)}}},scales:{y:{beginAtZero:true,ticks:{callback:value=>new Intl.NumberFormat('vi-VN',{notation:'compact'}).format(value)}}}}});
        table('revenueTrendBody',data.trend,[{value:r=>'Tháng '+r.month},{numeric:true,value:r=>r.future?'Chưa đến kỳ':money(r.current)},{numeric:true,value:r=>r.future?'—':money(r.previous)},{numeric:true,value:r=>r.future?'—':money(r.current-r.previous)}]);
        table('revenueCategoryBody',data.categories,[{value:r=>r.name},{numeric:true,value:r=>money(r.current)},{numeric:true,value:r=>money(r.previous)},{numeric:true,value:r=>money(r.delta)}]);
        table('revenuePaymentBody',data.payments,[{value:r=>payments[r.method]||r.method},{numeric:true,value:r=>count(r.orders)},{numeric:true,value:r=>money(r.revenue)}]);
        table('revenueProductBody',data.topProducts,[{value:r=>r.name},{numeric:true,value:r=>count(r.quantity)},{numeric:true,value:r=>count(r.freeQuantity)},{numeric:true,value:r=>money(r.grossRevenue)}]);
        $('revenueInsights').replaceChildren();data.insights.forEach(insight=>{const card=el('article',null,'card insight-card');card.append(el('h3',insight.title),el('p',insight.evidence,'insight-evidence'),el('p',insight.action));$('revenueInsights').append(card);});
        $('revenueNotes').replaceChildren(...data.notes.map(note=>el('li',note)));
    }
    $('revenueFilters').addEventListener('submit',event=>{event.preventDefault();loadRevenue();});
    $('revenueGranularity').addEventListener('change',()=>{const yearly=$('revenueGranularity').value==='year';$('monthField').hidden=yearly;$('revenueComparison').disabled=yearly;});
    $('exportRevenue').addEventListener('click',()=>{
        const d=state.report;if(!d)return;
        const rows=[['Báo cáo doanh thu',d.year],['Kỳ chọn',d.window.start,d.window.end],['Kỳ đối chiếu',d.window.previousStart,d.window.previousEnd],['Chỉ số','Kỳ chọn','Kỳ đối chiếu'],['Doanh thu',d.current.revenue,d.previous.revenue],['Hóa đơn',d.current.orders,d.previous.orders],['Giá trị đơn trung bình',d.current.average,d.previous.average],['Giảm giá',d.current.discount,d.previous.discount],[],['Tháng',d.year,d.year-1]];
        d.trend.forEach(r=>rows.push([r.month,r.future?'Chưa đến kỳ':r.current,r.future?'':r.previous]));rows.push([],['Nhóm hàng','Kỳ chọn','Kỳ đối chiếu','Chênh lệch']);d.categories.forEach(r=>rows.push([r.name,r.current,r.previous,r.delta]));rows.push([],['Lưu ý']);d.notes.forEach(note=>rows.push([note]));
        download('doanh-thu-'+d.year+'.csv',rows);
    });

    async function loadProducts() {
        try {
            const [categories,inventory]=await Promise.all([api('/categories'),api('/inventory')]);
            state.categories=categories;
            const previous=$('productCategory').value;$('productCategory').replaceChildren(new Option('Tất cả nhóm',''));categories.forEach(c=>$('productCategory').add(new Option(c.name,c.id)));$('productCategory').value=previous;
            const s=inventory.summary;$('inventoryKpis').replaceChildren(stat('Mã sản phẩm',count(s.products),'Gồm cả hàng chỉ còn trong lịch sử hóa đơn'),stat('Số lượng đang bán',count(s.units),'Tổng tồn của mặt hàng đang bật'),stat('Cần kiểm tra bổ sung',count(s.lowStock),'Mặt hàng đang bán có tồn ≤ 10'),stat('Hết hàng',count(s.outOfStock),'Mặt hàng đang bán có tồn ≤ 0'));
            await renderProducts();state.loaded.add('products');
        }catch(error){tableError('productBody',7,error.message);}
    }
    async function renderProducts() {
        const sequence=++state.productRequest;
        const query=new URLSearchParams({search:$('productSearch').value.trim(),filter:$('stockFilter').value,page:state.productPage,size:30});
        if($('productCategory').value)query.set('categoryId',$('productCategory').value);
        try{
            const data=await api('/products?'+query);if(sequence!==state.productRequest)return;
            state.products=data.items;
            const pages=Math.max(1,Math.ceil(data.total/data.size));
            $('productCount').textContent=count(data.total)+' sản phẩm';$('productPageInfo').textContent='Trang '+(data.page+1)+' / '+count(pages);
            $('productPrevious').disabled=data.page===0;$('productNext').disabled=data.page+1>=pages;
            table('productBody',data.items,[{value:p=>{const info=el('div',null,'product-info');info.append(el('strong',p.productName),el('small','#'+p.id+' · '+(p.barcode||'Chưa có mã')+(p.sourceTitle&&p.sourceTitle!==p.productName?' · Tên gốc: '+p.sourceTitle:'')));return info;}},{value:p=>state.categories.find(c=>c.id===p.categoryId)?.name||'Chưa phân loại'},{numeric:true,value:p=>money(p.unitPrice)},{numeric:true,value:p=>money(p.costPrice)},{numeric:true,value:p=>count(p.stockQuantity)},{value:p=>el('span',p.isActive?(p.stockQuantity<=0?'Hết hàng':'Đang bán'):'Ngừng bán',p.isActive&&p.stockQuantity<=10?'stock-warning':'payment-badge')},{value:p=>{const button=el('button','Chỉnh sửa','button button-secondary button-small');button.addEventListener('click',()=>editProduct(p));return button;}}],'Không có sản phẩm phù hợp.');
        }catch(error){if(sequence===state.productRequest)tableError('productBody',7,error.message);}
    }
    function editProduct(product) {
        const body=openDialog('Sửa sản phẩm #'+product.id);body.append(el('p','Tồn hiện tại: '+count(product.stockQuantity)+'. Giá mới áp dụng cho giao dịch sau khi lưu. Lịch sử hóa đơn giữ đơn giá đã bán.','muted'));
        const editor=saveForm(body,async form=>{
            const f=new FormData(form);await api('/products/'+product.id,writeOptions('PUT',{productName:f.get('productName'),barcode:f.get('barcode'),categoryId:f.get('categoryId')?Number(f.get('categoryId')):null,unitPrice:Number(f.get('unitPrice')),costPrice:f.get('costPrice')===''?null:Number(f.get('costPrice')),isActive:f.has('isActive')}));await loadProducts();
        });
        field(editor.form,'Tên sản phẩm','productName',product.productName,'text',{required:true,maxlength:255});field(editor.form,'Mã vạch','barcode',product.barcode,'text',{maxlength:50});
        select(editor.form,'Danh mục','categoryId',[['','Chưa phân loại'],...state.categories.map(c=>[c.id,c.name])],product.categoryId);
        field(editor.form,'Giá bán (đ)','unitPrice',product.unitPrice,'number',{required:true,min:'0.01',max:'9999999999.99',step:'0.01'});field(editor.form,'Giá vốn hiện tại (đ, có thể để trống)','costPrice',product.costPrice,'number',{min:0,max:'9999999999.99',step:'0.01'});checkbox(editor.form,'Đang bán','isActive',product.isActive);editor.finish();
    }
    let productTimer;
    $('productSearch').addEventListener('input',()=>{clearTimeout(productTimer);productTimer=setTimeout(()=>{state.productPage=0;renderProducts();},300);});
    ['stockFilter','productCategory'].forEach(id=>$(id).addEventListener('change',()=>{state.productPage=0;renderProducts();}));
    $('productPrevious').addEventListener('click',()=>{if(state.productPage>0){state.productPage--;renderProducts();}});$('productNext').addEventListener('click',()=>{state.productPage++;renderProducts();});
    $('addCategory').addEventListener('click',()=>{const body=openDialog('Thêm danh mục');const editor=saveForm(body,async form=>{await api('/categories',writeOptions('POST',{name:new FormData(form).get('name')}));await loadProducts();});field(editor.form,'Tên danh mục','name','','text',{required:true,maxlength:100});editor.finish();});

    async function loadCustomers() {
        const sequence=++state.customerRequest;tableError('customerBody',7,'Đang tải khách hàng…');$('customerCount').textContent='Đang tải…';
        try{
            const query=new URLSearchParams({search:$('customerSearch').value,page:state.customerPage});if($('customerSegment').value)query.set('segment',$('customerSegment').value);
            const data=await api('/customers?'+query);if(sequence!==state.customerRequest)return;
            $('customerCount').textContent=count(data.total)+' khách hàng';$('customerPageInfo').textContent='Trang '+(data.page+1)+' / '+Math.max(1,Math.ceil(data.total/data.size));
            $('customerPrevious').disabled=data.page===0;$('customerNext').disabled=(data.page+1)*data.size>=data.total;
            table('customerBody',data.items,[{value:r=>{const info=el('div',null,'product-info');info.append(el('strong',r.code||('#'+r.id)),el('small',r.name||'Chưa lưu tên'));return info;}},{value:r=>r.phone||'—'},{value:r=>r.segmentName || (r.segmentId==null?(r.firstPurchase?'Chưa xếp nhóm':'Chưa mua hàng'):'Nhóm #'+r.segmentId)},{numeric:true,value:r=>count(r.recordedOrders)},{numeric:true,value:r=>money(r.recordedRevenue)},{value:r=>r.firstPurchase?new Date(r.firstPurchase).toLocaleDateString('vi-VN'):'Chưa mua'},{value:r=>date(r.lastPurchase)}],'Không tìm thấy khách hàng phù hợp.');state.loaded.add('customers');
        }catch(error){if(sequence===state.customerRequest){tableError('customerBody',7,error.message);$('customerCount').textContent='Chưa tải được';$('customerPrevious').disabled=true;$('customerNext').disabled=true;}}
    }
    let searchTimer;
    $('customerSearch').addEventListener('input',()=>{clearTimeout(searchTimer);state.customerRequest++;searchTimer=setTimeout(()=>{state.customerPage=0;loadCustomers();},300);});
    $('customerSegment').addEventListener('change',()=>{state.customerPage=0;loadCustomers();});
    $('customerPrevious').addEventListener('click',()=>{if(state.customerPage>0){state.customerPage--;loadCustomers();}});$('customerNext').addEventListener('click',()=>{state.customerPage++;loadCustomers();});
    async function initCustomers(){try{const segments=await api('/segments');const value=$('customerSegment').value;$('customerSegment').replaceChildren(new Option('Tất cả nhóm',''));segments.forEach(s=>$('customerSegment').add(new Option(s.name,s.id)));$('customerSegment').value=value;}catch(error){notify(error.message,true);}await loadCustomers();}

    async function loadUsers(){
        if(window.storeUser?.role!=='ADMIN')return;
        try{state.users=await api('/users');table('userBody',state.users,[{value:u=>u.username},{value:u=>u.fullName},{value:u=>roles[u.role]||u.role||'Chưa gán quyền'},{value:u=>u.active?'Đang hoạt động':'Đã khóa'},{value:u=>{const button=el('button','Chỉnh sửa','button button-secondary button-small');button.addEventListener('click',()=>editUser(u));return button;}}]);state.loaded.add('users');}catch(error){tableError('userBody',5,error.message);}
    }
    function editUser(user){
        const body=openDialog(user?'Tài khoản '+user.username:'Tạo tài khoản');
        body.append(el('p','Mật khẩu cần ít nhất 10 ký tự và tối đa 72 byte UTF-8. '+(user?'Để trống nếu giữ mật khẩu hiện tại. Thay đổi mật khẩu kết thúc các phiên cũ.':''),'muted'));
        const editor=saveForm(body,async form=>{const f=new FormData(form);await api('/users'+(user?'/'+user.id:''),writeOptions(user?'PUT':'POST',{username:user?.username||f.get('username'),fullName:f.get('fullName'),password:f.get('password'),role:f.get('role'),active:f.has('active')}));await loadUsers();});
        if(!user)field(editor.form,'Tên đăng nhập','username','','text',{required:true,minlength:3,maxlength:50,pattern:'[A-Za-z0-9_.\\-]{3,50}',autocomplete:'off'});
        field(editor.form,'Họ và tên','fullName',user?.fullName,'text',{required:true,maxlength:100});field(editor.form,user?'Mật khẩu mới (tùy chọn)':'Mật khẩu','password','','password',{required:!user,minlength:10,maxlength:72,autocomplete:'new-password'});select(editor.form,'Vai trò','role',Object.entries(roles),user?.role||'CASHIER');checkbox(editor.form,'Tài khoản hoạt động','active',user?user.active:true);editor.finish();
    }
    $('addUser').addEventListener('click',()=>editUser(null));
    document.addEventListener('admin-order',async event=>{
        const body=openDialog('Hóa đơn '+event.detail);body.append(el('p','Đang tải chi tiết…'));
        try{
            const data=await api('/orders/'+encodeURIComponent(event.detail));if(!$('workspaceDialog').open)return;body.replaceChildren();const o=data.order;
            body.append(el('p','Thời gian: '+date(o.order_date)+' · Khách: '+(o.customerName||'Vãng lai / chưa lưu tên')+' · Thu ngân: '+(o.cashierName||'Chưa ghi nhận'),'detail-summary'));
            const wrap=el('div',null,'table-scroll'),t=el('table'),head=el('thead'),hr=el('tr'),tbody=el('tbody');tbody.id='orderDetailItems';['Sản phẩm','Số lượng','Đơn giá','Thành tiền'].forEach(text=>hr.append(el('th',text)));head.append(hr);t.append(head,tbody);wrap.append(t);body.append(wrap);
            table('orderDetailItems',data.items,[{value:r=>(r.name||'#'+r.productId)+(Number(r.unitPrice)===0?' · Giá 0':'')},{numeric:true,value:r=>count(r.quantity)},{numeric:true,value:r=>money(r.unitPrice)},{numeric:true,value:r=>money(r.revenue)}],'Hóa đơn chưa có dòng chi tiết trong cơ sở dữ liệu.');
            body.append(el('p','Tạm tính '+money(o.total_amount)+' · Giảm '+money(o.discount_amount)+' · Doanh thu '+money(o.final_amount),'detail-total'));
        }catch(error){body.replaceChildren(el('p',error.message,'form-error'));}
    });
    async function loadShifts(){
        try{
            const query=new URLSearchParams();if($('shiftFrom').value)query.set('from',$('shiftFrom').value);if($('shiftTo').value)query.set('to',$('shiftTo').value);if($('shiftCashier').value)query.set('cashierId',$('shiftCashier').value);
            const data=await api('/shifts?'+query);
            const previous=$('shiftCashier').value;$('shiftCashier').replaceChildren(new Option('Tất cả',''));data.cashiers.forEach(c=>$('shiftCashier').add(new Option(c.fullName+' ('+c.username+')',c.id)));$('shiftCashier').value=previous;
            $('shiftCount').textContent=count(data.items.length)+' ca'+(data.items.length>=200?' (200 ca gần nhất)':'');
            table('shiftBody',data.items,[{value:r=>'#'+r.id},{value:r=>r.cashierName+' ('+r.username+')'},{value:r=>date(r.openedAt)},{value:r=>r.closedAt?date(r.closedAt):'—'},{numeric:true,value:r=>count(r.orders)},{numeric:true,value:r=>money(r.revenue)},{numeric:true,value:r=>money(r.cashRevenue)},{value:r=>el('span',r.status==='OPEN'?'Đang mở':'Đã kết ca',r.status==='OPEN'?'stock-warning':'payment-badge')},{value:r=>{const button=el('button','Xem báo cáo','button button-secondary button-small');button.addEventListener('click',()=>openShift(r.id));return button;}}],'Chưa có ca làm việc nào. Nhân viên mở ca tại quầy POS.');
            state.loaded.add('shifts');
        }catch(error){tableError('shiftBody',9,error.message);}
    }
    async function openShift(id){
        const body=openDialog('Báo cáo ca #'+id);body.append(el('p','Đang tải báo cáo…'));
        try{
            const report=await api('/shifts/'+id);state.shiftReport=report;if(!$('workspaceDialog').open)return;
            body.replaceChildren();const holder=el('div',null,'print-shift');ShiftReport.render(holder,report);body.append(holder);
            const actions=el('div',null,'shift-report-actions no-print');
            const revenue=el('button','Xuất doanh thu (CSV)','button button-secondary button-small');revenue.addEventListener('click',()=>ShiftReport.exportRevenue(report));
            const gifts=el('button','Xuất quà tặng (CSV)','button button-secondary button-small');gifts.addEventListener('click',()=>ShiftReport.exportGifts(report));
            const print=el('button','In báo cáo','button button-secondary button-small');print.addEventListener('click',()=>window.print());
            actions.append(revenue,gifts,print);
            if(report.shift.status==='OPEN'){
                const close=el('button','Kết ca thay nhân viên','button button-primary button-small');
                close.addEventListener('click',async()=>{
                    const counted=prompt('Tiền mặt đếm được trong két (đ). Để trống nếu chưa đếm:', String(Math.round(Number(report.cash.expected)||0)));
                    if(counted===null)return;
                    try{await api('/shifts/'+id+'/close',writeOptions('POST',{countedCash:counted===''?null:Number(counted),note:'Quản lý kết ca thay nhân viên'}));notify('Đã kết ca #'+id+'.');await loadShifts();await openShift(id);}catch(error){notify(error.message,true);}
                });
                actions.append(close);
            }
            body.append(actions);
        }catch(error){body.replaceChildren(el('p',error.message,'form-error'));}
    }
    ['shiftFrom','shiftTo','shiftCashier'].forEach(id=>$(id).addEventListener('change',loadShifts));
    async function loadTab(tab,force=false){
        if(!force&&state.loaded.has(tab))return;
        if(tab==='revenue')await initRevenue();if(tab==='products')await loadProducts();if(tab==='customers')await initCustomers();if(tab==='users')await loadUsers();if(tab==='shifts')await loadShifts();
        if(tab==='revenue'&&state.report)state.loaded.add(tab);
    }
    document.addEventListener('admin-tab',event=>{state.tab=event.detail;loadTab(state.tab);if(state.tab==='revenue')requestAnimationFrame(()=>state.chart?.resize());});
    document.addEventListener('admin-refresh',()=>{state.loaded.delete('products');state.loaded.delete('customers');state.loaded.delete('users');state.loaded.delete('revenue');state.loaded.delete('shifts');loadTab(state.tab,true);});
    document.addEventListener('admin-clusters-applied',()=>{state.loaded.delete('customers');state.customerPage=0;initCustomers();});
})();
