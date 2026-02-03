// Configuration
const SERVER_URL = 'http://localhost:9661';
const API_ENDPOINTS = {
    text: `${SERVER_URL}/api/push/text`,
    file: `${SERVER_URL}/api/push/file`
};

// State
let messageHistory = [];

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    checkServerStatus();
    loadHistory();
    // Check server status every 10 seconds
    setInterval(checkServerStatus, 10000);
});

// Check server status
async function checkServerStatus() {
    const indicator = document.getElementById('statusIndicator');
    const statusText = document.getElementById('statusText');

    try {
        const response = await fetch(SERVER_URL, { method: 'HEAD' });
        if (response.ok || response.status === 404) {
            indicator.className = 'status-indicator online';
            statusText.textContent = 'Server Online';
        } else {
            indicator.className = 'status-indicator offline';
            statusText.textContent = 'Server Error';
        }
    } catch (error) {
        indicator.className = 'status-indicator offline';
        statusText.textContent = 'Server Offline';
    }
}

// Send text message
async function sendText() {
    const input = document.getElementById('textInput');
    const content = input.value.trim();

    if (!content) {
        alert('Please enter some text');
        return;
    }

    try {
        const response = await fetch(API_ENDPOINTS.text, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ content })
        });

        const data = await response.json();

        if (response.ok) {
            addToHistory('text', `Text sent: ${content.substring(0, 50)}${content.length > 50 ? '...' : ''}`, true);
            input.value = '';
            showNotification('Text sent successfully!', 'success');
        } else {
            throw new Error(data.error || 'Failed to send text');
        }
    } catch (error) {
        addToHistory('text', `Error: ${error.message}`, false);
        showNotification(error.message, 'error');
    }
}

// Send quick text
async function sendQuickText(text) {
    document.getElementById('textInput').value = text;
    await sendText();
}

// Clear text
function clearText() {
    document.getElementById('textInput').value = '';
}

// Preview image
function previewImage(input) {
    const preview = document.getElementById('imagePreview');
    const sendBtn = document.getElementById('sendImageBtn');

    if (input.files && input.files[0]) {
        const file = input.files[0];

        if (!file.type.startsWith('image/')) {
            alert('Please select an image file');
            input.value = '';
            return;
        }

        const reader = new FileReader();
        reader.onload = (e) => {
            preview.innerHTML = `<img src="${e.target.result}" alt="Preview">`;
            preview.classList.add('active');
            sendBtn.disabled = false;
        };
        reader.readAsDataURL(file);
    }
}

// Send image
async function sendImage() {
    const input = document.getElementById('imageInput');

    if (!input.files || !input.files[0]) {
        alert('Please select an image');
        return;
    }

    const formData = new FormData();
    formData.append('file', input.files[0]);

    try {
        const response = await fetch(API_ENDPOINTS.file, {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (response.ok) {
            addToHistory('image', `Image sent: ${input.files[0].name}`, true);
            clearImage();
            showNotification('Image sent successfully!', 'success');
        } else {
            throw new Error(data.error || 'Failed to send image');
        }
    } catch (error) {
        addToHistory('image', `Error: ${error.message}`, false);
        showNotification(error.message, 'error');
    }
}

// Clear image
function clearImage() {
    document.getElementById('imageInput').value = '';
    document.getElementById('imagePreview').innerHTML = '';
    document.getElementById('imagePreview').classList.remove('active');
    document.getElementById('sendImageBtn').disabled = true;
}

// Preview file
function previewFile(input) {
    const preview = document.getElementById('filePreview');
    const sendBtn = document.getElementById('sendFileBtn');

    if (input.files && input.files[0]) {
        const file = input.files[0];
        const size = (file.size / 1024).toFixed(2);
        const icon = getFileIcon(file.name);

        preview.innerHTML = `
            <div class="preview-info">
                <span class="preview-icon">${icon}</span>
                <div>
                    <div><strong>${file.name}</strong></div>
                    <div style="font-size: 12px; color: #999;">${size} KB</div>
                </div>
            </div>
        `;
        preview.classList.add('active');
        sendBtn.disabled = false;
    }
}

// Send file
async function sendFile() {
    const input = document.getElementById('fileInput');

    if (!input.files || !input.files[0]) {
        alert('Please select a file');
        return;
    }

    const formData = new FormData();
    formData.append('file', input.files[0]);

    try {
        const response = await fetch(API_ENDPOINTS.file, {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (response.ok) {
            addToHistory('file', `File sent: ${input.files[0].name}`, true);
            clearFile();
            showNotification('File sent successfully!', 'success');
        } else {
            throw new Error(data.error || 'Failed to send file');
        }
    } catch (error) {
        addToHistory('file', `Error: ${error.message}`, false);
        showNotification(error.message, 'error');
    }
}

// Clear file
function clearFile() {
    document.getElementById('fileInput').value = '';
    document.getElementById('filePreview').innerHTML = '';
    document.getElementById('filePreview').classList.remove('active');
    document.getElementById('sendFileBtn').disabled = true;
}

// Get file icon
function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const icons = {
        'pdf': '📄',
        'doc': '📝', 'docx': '📝',
        'xls': '📊', 'xlsx': '📊',
        'ppt': '📊', 'pptx': '📊',
        'zip': '🗜️', 'rar': '🗜️', '7z': '🗜️',
        'txt': '📃',
        'mp3': '🎵', 'wav': '🎵',
        'mp4': '🎬', 'avi': '🎬',
        'jpg': '🖼️', 'png': '🖼️', 'gif': '🖼️'
    };
    return icons[ext] || '📁';
}

// Add to history
function addToHistory(type, message, success) {
    const timestamp = new Date().toLocaleTimeString();
    const item = {
        type,
        message,
        success,
        timestamp
    };

    messageHistory.unshift(item);
    if (messageHistory.length > 50) {
        messageHistory.pop();
    }

    saveHistory();
    renderHistory();
}

// Render history
function renderHistory() {
    const container = document.getElementById('messageHistory');

    if (messageHistory.length === 0) {
        container.innerHTML = '<p style="text-align: center; color: #999;">No messages yet</p>';
        return;
    }

    container.innerHTML = messageHistory.map(item => `
        <div class="message-item ${item.success ? 'success' : 'error'}">
            <div class="message-time">
                <span class="message-type ${item.type}">${item.type.toUpperCase()}</span>
                ${item.timestamp}
            </div>
            <div class="message-content">${escapeHtml(item.message)}</div>
        </div>
    `).join('');
}

// Clear history
function clearHistory() {
    if (confirm('Clear all message history?')) {
        messageHistory = [];
        saveHistory();
        renderHistory();
    }
}

// Save history to localStorage
function saveHistory() {
    localStorage.setItem('clipboardManHistory', JSON.stringify(messageHistory));
}

// Load history from localStorage
function loadHistory() {
    const saved = localStorage.getItem('clipboardManHistory');
    if (saved) {
        try {
            messageHistory = JSON.parse(saved);
            renderHistory();
        } catch (e) {
            console.error('Failed to load history:', e);
        }
    }
}

// Show notification
function showNotification(message, type) {
    // Simple alert for now, can be enhanced with toast notifications
    if (type === 'success') {
        console.log('✓', message);
    } else {
        console.error('✗', message);
    }
}

// Escape HTML
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Keyboard shortcuts
document.addEventListener('keydown', (e) => {
    // Ctrl/Cmd + Enter to send text
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        if (document.activeElement.id === 'textInput') {
            sendText();
        }
    }
});
