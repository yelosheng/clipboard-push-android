# ... (imports remain the same)
import os
import time
import json
import logging
from flask import Flask, request, jsonify, render_template, redirect, url_for, flash, send_from_directory
from flask_socketio import SocketIO, emit, join_room, leave_room
from flask_login import LoginManager, UserMixin, login_user, login_required, logout_user, current_user
import boto3
from botocore.config import Config
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

# --- Configuration ---
# In production, these should be loaded from Environment Variables
R2_ACCOUNT_ID = os.environ.get('R2_ACCOUNT_ID', 'YOUR_ACCOUNT_ID_HERE')
R2_ACCESS_KEY_ID = os.environ.get('R2_ACCESS_KEY_ID', 'YOUR_ACCESS_KEY_HERE')
R2_SECRET_ACCESS_KEY = os.environ.get('R2_SECRET_ACCESS_KEY', 'YOUR_SECRET_KEY_HERE')
R2_BUCKET_NAME = os.environ.get('R2_BUCKET_NAME', 'clipboard-man-relay')
ADMIN_PASSWORD = os.environ.get('ADMIN_PASSWORD', 'admin') # Default password

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__, static_folder='static', template_folder='templates')
app.config['SECRET_KEY'] = os.environ.get('FLASK_SECRET_KEY', 'dev_secret_key')

# Initialize LoginManager
login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = 'login'

# Initialize SocketIO with standard threading (most compatible)
socketio = SocketIO(app, cors_allowed_origins="*")

import urllib3
# Disable SSL warnings because we are using verify=False to fix local proxy issues
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Initialize S3 Client (Cloudflare R2)
s3_client = boto3.client(
    's3',
    endpoint_url=f'https://{R2_ACCOUNT_ID}.r2.cloudflarestorage.com',
    aws_access_key_id=R2_ACCESS_KEY_ID,
    aws_secret_access_key=R2_SECRET_ACCESS_KEY,
    config=Config(signature_version='s3v4'),
    region_name='auto', # R2 specific
    verify=False # FIXME: SSL validation fails in this env, likely due to proxy
)

# Verify R2 Connection on Startup
try:
    logger.info(f"Verifying R2 Connection to bucket: {R2_BUCKET_NAME}...")
    s3_client.head_bucket(Bucket=R2_BUCKET_NAME)
    logger.info("✅ R2 Connection Successful!")
except Exception as e:
    logger.error(f"❌ R2 Connection Failed: {e}")
    # Don't exit, just log error so we can debug

# Global mapping to track client_id -> list of sids
# Structure: { 'client_id_1': {'sid1', 'sid2'}, ... }
CLIENT_SESSIONS = {}
# Structure: { 'client_id_1': 'room_name', ... }
CLIENT_ROOMS = {}

def get_serialized_sessions():
    """Convert sets to lists for JSON serialization and include room info"""
    data = {}
    for client_id, sids in CLIENT_SESSIONS.items():
        data[client_id] = {
            'sids': list(sids),
            'room': CLIENT_ROOMS.get(client_id, 'Unknown')
        }
    return data

def get_client_from_sid(sid):
    """Reverse lookup to find client_id from sid"""
    for client_id, sids in CLIENT_SESSIONS.items():
        if sid in sids:
            return client_id
    return "Unknown"

def broadcast_room_stats(room):
    """Broadcast the number of unique clients (devices) in the room."""
    if not room: return
    
    # Count unique client_ids in this room
    count = sum(1 for r in CLIENT_ROOMS.values() if r == room)
    
    socketio.emit('room_stats', {'count': count, 'room': room}, room=room)
    logger.info(f"Broadcast room_stats to {room}: {count} clients")


# --- Auth Logic ---

class User(UserMixin):
    def __init__(self, id):
        self.id = id

@login_manager.user_loader
def load_user(user_id):
    if user_id == 'admin':
        return User(user_id)
    return None

@app.route('/login', methods=['GET', 'POST'])
def login():
    if current_user.is_authenticated:
        return redirect(url_for('dashboard'))
    
    if request.method == 'POST':
        password = request.form.get('password')
        remember = True if request.form.get('remember') else False
        
        if password == ADMIN_PASSWORD:
            user = User('admin')
            login_user(user, remember=remember)
            return redirect(url_for('dashboard'))
        else:
            flash('Invalid password')
            
    return render_template('login.html')

@app.route('/logout')
@login_required
def logout():
    logout_user()
    return redirect(url_for('login'))

@app.route('/dashboard')
@login_required
def dashboard():
    return render_template('dashboard.html', client_sessions=get_serialized_sessions())

@app.route('/')
def index():
    if current_user.is_authenticated:
        return redirect(url_for('dashboard'))
    return "Clipboard Push Relay Server is Running (Port 5055). 🚀 <a href='/login'>Login</a>"

@app.route('/favicon.ico')
def favicon():
    return send_from_directory(os.path.join(app.root_path, 'static'),
                               'favicon.png', mimetype='image/vnd.microsoft.icon')

# --- File Transfer Logic (R2 Presigned URLs) ---

@app.route('/api/file/upload_auth', methods=['POST'])
def generate_upload_url():
    """
    Client requests permission to upload a file.
    Server returns a Presigned PUT URL.
    Client uploads directly to R2.
    """
    data = request.json
    filename = data.get('filename')
    content_type = data.get('content_type', 'application/octet-stream')
    
    if not filename:
        return jsonify({'error': 'Filename required'}), 400

    # Generate a unique object name (prevent collisions)
    # Format: timestamp_random_filename
    object_name = f"{int(time.time())}_{filename}"

    try:
        # Generate Presigned URL for PUT (Upload)
        # Expiration: 3600 seconds (1 hour) or less
        presigned_url = s3_client.generate_presigned_url(
            'put_object',
            Params={
                'Bucket': R2_BUCKET_NAME,
                'Key': object_name,
                'ContentType': content_type
            },
            ExpiresIn=300
        )
        
        # Also generate a Presigned URL for GET (Download) immediately
        # The client will send this download URL to the receiver via SocketIO
        download_url = s3_client.generate_presigned_url(
            'get_object',
            Params={
                'Bucket': R2_BUCKET_NAME,
                'Key': object_name
            },
            ExpiresIn=3600 # Valid for 1 hour (lifecycle matching)
        )

        return jsonify({
            'upload_url': presigned_url,
            'download_url': download_url,
            'file_key': object_name,
            'expires_in': 300
        })

    except Exception as e:
        logger.error(f"Error generating presigned URL: {e}")
        return jsonify({'error': str(e)}), 500

# --- Real-time Signaling Logic (Socket.IO) ---

@socketio.on('connect')
def on_connect():
    logger.info(f"Client connected: {request.sid}")
    # Notify dashboard of new connection (if we want real-time list updates)
    socketio.emit('server_stats', {'clients': len(CLIENT_SESSIONS), 'msg': 'New connection'}, room='dashboard_room')

@socketio.on('disconnect')
def on_disconnect():
    logger.info(f"Client disconnected: {request.sid}")
    # Remove SID from CLIENT_SESSIONS
    for client_id, sids in list(CLIENT_SESSIONS.items()):
        if request.sid in sids:
            sids.discard(request.sid)
            if not sids:
                del CLIENT_SESSIONS[client_id]
                # Clean up room info if no sessions left
                if client_id in CLIENT_ROOMS:
                    room = CLIENT_ROOMS[client_id]
                    del CLIENT_ROOMS[client_id]
                    # Broadcast updated stats (after removal)
                    broadcast_room_stats(room)
            
            logger.info(f"Removed SID {request.sid} from client {client_id}")
            # Notify dashboard
            socketio.emit('client_list_update', get_serialized_sessions(), room='dashboard_room')
                
            break
    socketio.emit('server_stats', {'clients': len(CLIENT_SESSIONS), 'msg': 'Client disconnected'}, room='dashboard_room')

@socketio.on('join')
def on_join(data):
    room = data.get('room')
    client_id = data.get('client_id')
    
    if room:
        join_room(room)
        emit('status', {'msg': f'Joined room: {room}'}, room=room)
        logger.info(f"Client {request.sid} joined room: {room}")
        
        # If joining the dashboard, send immediate state
        if room == 'dashboard_room':
            serialized_sessions = get_serialized_sessions()
            logger.info(f"Dashboard joined. Sending immediate update to {request.sid}: {serialized_sessions}")
            emit('client_list_update', serialized_sessions, room=request.sid)
        
    if client_id:
        if client_id not in CLIENT_SESSIONS:
            CLIENT_SESSIONS[client_id] = set()
        CLIENT_SESSIONS[client_id].add(request.sid)
        
        # Track room for mixed dashboard view
        if room:
            CLIENT_ROOMS[client_id] = room
            logger.info(f"Updated room for {client_id}: {room}")
        else:
            logger.warning(f"Client {client_id} joined without room info in payload")
            
        logger.info(f"Registered client_id {client_id} with sid {request.sid}. Current Rooms: {CLIENT_ROOMS}")
        # Broadcast updated client list to dashboard
        serialized_sessions = get_serialized_sessions()
        logger.info(f"Broadcasting update to dashboard_room: {serialized_sessions}")
        socketio.emit('client_list_update', serialized_sessions, room='dashboard_room')
        
        # Broadcast room stats
        if room:
            broadcast_room_stats(room)

@socketio.on('leave')
def on_leave(data):
    room = data.get('room')
    if room:
        leave_room(room)
        emit('status', {'msg': f'Left room: {room}'}, room=room)
        logger.info(f"Client left room: {room}")
        broadcast_room_stats(room)

@socketio.on('clipboard_push')
def handle_clipboard_push(data):
    """
    Relay clipboard data (Text) to everyone in the room.
    Data should be E2EE encrypted by the client.
    """
    room = data.get('room')
    # Validates that room exists in data
    if room:
        # Broadcast to everyone ELSE in request room
        # include_self=False is default in some versions, but explicit is good.
        emit('clipboard_sync', data, room=room, include_self=False)
        logger.info(f"Relayed clipboard data to room: {room}")
        
        # Notify dashboard
        sender = get_client_from_sid(request.sid)
        content_preview = data.get('content', '')[:30] + '...' if data.get('content') else 'Encrypted Data'
        socketio.emit('activity_log', {
            'type': 'clipboard', 
            'room': room, 
            'sender': sender,
            'content': content_preview
        }, room='dashboard_room')

@socketio.on('file_push')
def handle_file_push(data):
    """
    Relay file metadata (Download URL, Decryption Key) to everyone in the room.
    Content is NOT here, only the link to R2.
    """
    room = data.get('room')
    if room:
        emit('file_sync', data, room=room, include_self=False)
        logger.info(f"Relayed file metadata to room: {room}")
        
        # Notify dashboard
        # Notify dashboard
        sender = get_client_from_sid(request.sid)
        filename = data.get('filename', 'Unknown File')
        socketio.emit('activity_log', {
            'type': 'file', 
            'room': room, 
            'sender': sender,
            'content': filename
        }, room='dashboard_room')

@app.route('/api/relay', methods=['POST'])
def relay_message():
    """
    Stateless relay endpoint for Push Tools (CLI/Shortcuts).
    Accepts JSON: { "room": "...", "event": "...", "data": ..., "sender_id": "..." }
    Broadcasts via Socket.IO to the specified room.
    To prevent echo, provide 'sender_id' in the JSON body.
    """
    try:
        content = request.json
        room = content.get('room')
        event = content.get('event')
        data = content.get('data')
        sender_id = content.get('sender_id') or content.get('client_id')

        if not room or not event or data is None:
            return jsonify({'error': 'Missing room, event, or data'}), 400

        # Determine sids to skip if sender_id is provided
        skip_sids = []
        if sender_id and sender_id in CLIENT_SESSIONS:
            skip_sids = list(CLIENT_SESSIONS[sender_id])
            logger.info(f"Skipping sids for sender {sender_id}: {skip_sids}")

        # Broadcast to room
        # Note: socketio.emit doesn't have exclude_self (that's for context-aware emit)
        # But it has skip_sid which accepts a list of SIDs to skip.
        if skip_sids:
            socketio.emit(event, data, room=room, skip_sid=skip_sids)
        else:
            socketio.emit(event, data, room=room)
            
        logger.info(f"Relayed HTTP message to room {room}: event={event}, skipped={len(skip_sids)}")
        
        # Notify dashboard
        # Notify dashboard
        socketio.emit('activity_log', {
            'type': 'api_relay', 
            'room': room, 
            'sender': sender_id or 'API',
            'content': f"Event: {event}"
        }, room='dashboard_room')
        
        return jsonify({'status': 'ok'}), 200

    except Exception as e:
        logger.error(f"Relay error: {e}")
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    # use_reloader=False to prevent double execution and potential SSL issues on startup
    socketio.run(app, host='0.0.0.0', port=5055, debug=True, use_reloader=False)
