'use strict';
(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, options = {}) => {
        const url = new URL(typeof input === 'string' ? input : input.url, location.origin);
        if (url.origin === location.origin) {
            const headers = new Headers(options.headers || (input instanceof Request ? input.headers : undefined));
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            if (token && header && !/^(GET|HEAD|OPTIONS)$/i.test(options.method || (input instanceof Request ? input.method : 'GET'))) headers.set(header, token);
            options = { ...options, headers };
        }
        const response = await originalFetch(input, options);
        if (response.status === 401 && url.origin === location.origin) location.assign('/login');
        return response;
    };
    document.addEventListener('DOMContentLoaded', async () => {
        try {
            const response = await fetch('/api/auth/me');
            if (!response.ok) return;
            const user = await response.json();
            window.storeUser = user;
            const labels = { ADMIN: 'Quản trị viên', MANAGER: 'Quản lý', CASHIER: 'Thu ngân' };
            document.querySelectorAll('[data-profile-name]').forEach(node => { node.textContent = user.fullName + ' · ' + (labels[user.role] || user.role); });
            document.querySelectorAll('[data-admin-only]').forEach(node => { node.hidden = user.role !== 'ADMIN'; });
            document.querySelectorAll('[data-management-only]').forEach(node => { node.hidden = user.role === 'CASHIER'; });
            document.dispatchEvent(new CustomEvent('store-session', { detail: user }));
        } catch { /* Existing data status communicates connection failures. */ }
    });
})();
