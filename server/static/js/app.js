document.addEventListener('DOMContentLoaded', () => {
    console.log("%c SWISS DASHBOARD v2.0 LOADED ", "background: #000; color: #fff; font-size: 20px; font-weight: bold; padding: 10px;");

    // State
    let currentClients = {};
    let selectedRoom = 'all';

    const socket = io();

    // Elements
    const roomListEl = document.getElementById('room-list');
    const clientListEl = document.getElementById('client-list');
    const totalConnEl = document.getElementById('total-connections');
    const totalSessEl = document.getElementById('total-sessions');
    const uptimeEl = document.getElementById('uptime');
    const logEl = document.getElementById('activity-log');
    const statusDot = document.getElementById('connection-status');
    const roomTitle = document.getElementById('current-room-title');

    // Uptime
    let seconds = 0;
    setInterval(() => {
        seconds++;
        const h = Math.floor(seconds / 3600).toString().padStart(2, '0');
        const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0');
        const s = (seconds % 60).toString().padStart(2, '0');
        uptimeEl.textContent = `${h}:${m}:${s}`;
    }, 1000);

    // --- Socket ---

    socket.on('connect', () => {
        log('SYS', 'N/A', 'System', 'Clean connection established.');
        statusDot.className = 'status-indicator green';
        socket.emit('join', { room: 'dashboard_room' });
    });

    socket.on('disconnect', () => {
        log('ERR', 'N/A', 'System', 'Connection lost.');
        statusDot.className = 'status-indicator'; // gray/black
    });

    socket.on('client_list_update', (clients) => {
        console.log('Received client_list_update:', clients);
        log('DEBUG', 'N/A', 'System', 'Received client list update');
        currentClients = clients;
        render();
    });

    socket.on('activity_log', (data) => {
        const room = data.room || 'Unknown';
        const sender = data.sender || 'Unknown';
        const type = (data.type || 'INFO').toUpperCase();
        const content = data.content || '';

        log(type, room, sender, content);
    });

    // --- Render ---

    function render() {
        // ... (existing render logic)

        const rooms = {};
        let totalC = 0;
        let totalS = 0;

        // Group
        Object.keys(currentClients).forEach(cid => {
            const c = currentClients[cid];
            let r = 'Unknown';
            let sids = [];

            if (Array.isArray(c)) { sids = c; }
            else { sids = c.sids || []; r = c.room || 'Unknown'; }
            const t = normalizeClientType(c);

            if (!rooms[r]) rooms[r] = [];
            rooms[r].push({ id: cid, sids, room: r, type: t });

            totalC++;
            totalS += sids.length;
        });

        totalConnEl.textContent = totalC;
        totalSessEl.textContent = totalS;

        renderSidebar(rooms, totalC);
        renderTable(rooms);
    }

    function renderSidebar(rooms, total) {
        let html = `
            <li class="nav-item ${selectedRoom === 'all' ? 'active' : ''}" onclick="selectRoom('all')">
                <span>All Rooms</span>
                <span class="nav-badge">${total}</span>
            </li>
        `;

        Object.keys(rooms).sort().forEach(r => {
            const count = rooms[r].length;
            const active = selectedRoom === r ? 'active' : '';
            html += `
                <li class="nav-item ${active}" onclick="selectRoom('${r}')">
                    <span>${r}</span>
                    <span class="nav-badge">${count}</span>
                </li>
            `;
        });

        roomListEl.innerHTML = html;
    }

    function renderTable(rooms) {
        clientListEl.innerHTML = '';
        let list = [];

        if (selectedRoom === 'all') {
            Object.values(rooms).forEach(arr => list.push(...arr));
            roomTitle.textContent = 'All Rooms Overview';
        } else {
            list = rooms[selectedRoom] || [];
            roomTitle.textContent = `Room: ${selectedRoom}`;
        }

        if (list.length === 0) {
            clientListEl.innerHTML = '<tr><td colspan="5" style="color: #999; padding: 24px 0;">No active clients in this view.</td></tr>';
            return;
        }

        list.forEach(c => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td style="font-weight: 700;">${c.id}</td>
                <td>${renderClientType(c.type)}</td>
                <td>${c.room}</td>
                <td>${c.sids.length}</td>
                <td><span style="color: #34c759; font-weight: 700;">Active</span></td>
            `;
            clientListEl.appendChild(tr);
        });
    }

    // --- Utils ---

    window.selectRoom = (r) => {
        selectedRoom = r;
        render();
    };

    function log(type, room, sender, content) {
        const div = document.createElement('div');
        div.className = 'log-item';

        const senderColor = stringToColor(sender);

        div.innerHTML = `
            <span class="log-time">${new Date().toLocaleTimeString('en-GB')}</span>
            <span class="log-tag">[${type}]</span>
            <span style="color: ${senderColor}; font-weight: 700; margin-right: 8px;">[${sender}]</span>
            <span class="log-content">${content}</span>
        `;
        logEl.insertBefore(div, logEl.firstChild);
        if (logEl.children.length > 50) logEl.removeChild(logEl.lastChild);
    }

    function normalizeClientType(c) {
        if (!c) return 'unknown';
        const raw = c.type || c.client_type || c.clientType || 'unknown';
        return String(raw).toLowerCase();
    }

    function renderClientType(type) {
        const t = (type || 'unknown').toLowerCase();
        const label = t.toUpperCase();
        const icon = typeIconChar(t);
        return `
            <span class="type-chip type-${t}">
                <span class="type-icon" aria-hidden="true">${icon}</span>
                <span class="type-label">${label}</span>
            </span>
        `;
    }

    function typeIconChar(t) {
        switch (t) {
            case 'windows': return 'W';
            case 'macos': return 'M';
            case 'linux': return 'L';
            case 'android': return 'A';
            case 'ios': return 'I';
            case 'web': return 'B';
            case 'cli': return '>';
            default: return '?';
        }
    }

    function stringToColor(str) {
        if (!str) return '#888888';
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            hash = str.charCodeAt(i) + ((hash << 5) - hash);
        }

        // Convert to easy-to-read dark colors (for white bg)
        // We want high contrast against white, so avoid very light colors
        const c = (hash & 0x00FFFFFF)
            .toString(16)
            .toUpperCase();

        return '#' + "00000".substring(0, 6 - c.length) + c;
    }
});
