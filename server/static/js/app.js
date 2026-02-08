document.addEventListener('DOMContentLoaded', () => {
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
        log('SYS', 'Clean connection established.');
        statusDot.className = 'status-indicator green';
        socket.emit('join', { room: 'dashboard_room' });
    });

    socket.on('disconnect', () => {
        log('ERR', 'Connection lost.');
        statusDot.className = 'status-indicator'; // gray/black
    });

    socket.on('client_list_update', (clients) => {
        currentClients = clients;
        render();
    });

    socket.on('activity_log', (data) => {
        const room = data.room || 'Unknown';
        const event = data.event || data.type;
        log(event.toUpperCase(), `Activity in ${room}`);
    });

    // --- Render ---

    function render() {
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

            if (!rooms[r]) rooms[r] = [];
            rooms[r].push({ id: cid, sids, room: r });

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
            clientListEl.innerHTML = '<tr><td colspan="4" style="color: #999; padding: 24px 0;">No active clients in this view.</td></tr>';
            return;
        }

        list.forEach(c => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td style="font-weight: 700;">${c.id}</td>
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

    function log(tag, msg) {
        const div = document.createElement('div');
        div.className = 'log-item';
        div.innerHTML = `
            <span class="log-time">${new Date().toLocaleTimeString('en-GB')}</span>
            <span class="log-tag">${tag}</span>
            <span class="log-content">${msg}</span>
        `;
        logEl.insertBefore(div, logEl.firstChild);
        if (logEl.children.length > 20) logEl.removeChild(logEl.lastChild);
    }
});
