import os
import time
import json
import logging
from flask import Flask, request, jsonify
from flask_socketio import SocketIO, emit, join_room, leave_room
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

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
app.config['SECRET_KEY'] = os.environ.get('FLASK_SECRET_KEY', 'dev_secret_key')

# Initialize SocketIO with standard threading (most compatible)
socketio = SocketIO(app, cors_allowed_origins="*")

# Initialize S3 Client (Cloudflare R2)
s3_client = boto3.client(
    's3',
    endpoint_url=f'https://{R2_ACCOUNT_ID}.r2.cloudflarestorage.com',
    aws_access_key_id=R2_ACCESS_KEY_ID,
    aws_secret_access_key=R2_SECRET_ACCESS_KEY,
    config=Config(signature_version='s3v4'),
    region_name='auto' # R2 specific
)

# Verify R2 Connection on Startup
try:
    logger.info(f"Verifying R2 Connection to bucket: {R2_BUCKET_NAME}...")
    s3_client.head_bucket(Bucket=R2_BUCKET_NAME)
    logger.info("✅ R2 Connection Successful!")
except Exception as e:
    logger.error(f"❌ R2 Connection Failed: {e}")
    # Don't exit, just log error so we can debug
    
@app.route('/')
def index():
    return "Clipboard Push Relay Server is Running. 🚀"

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

@socketio.on('join')
def on_join(data):
    room = data.get('room')
    if room:
        join_room(room)
        emit('status', {'msg': f'Joined room: {room}'}, room=room)
        logger.info(f"Client joined room: {room}")

@socketio.on('leave')
def on_leave(data):
    room = data.get('room')
    if room:
        leave_room(room)
        emit('status', {'msg': f'Left room: {room}'}, room=room)
        logger.info(f"Client left room: {room}")

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

@app.route('/api/relay', methods=['POST'])
def relay_message():
    """
    Stateless relay endpoint for Push Tools (CLI/Shortcuts).
    Accepts JSON: { "room": "...", "event": "...", "data": ... }
    Broadcasts via Socket.IO to the specified room.
    """
    try:
        content = request.json
        room = content.get('room')
        event = content.get('event')
        data = content.get('data')

        if not room or not event or data is None:
            return jsonify({'error': 'Missing room, event, or data'}), 400

        # Broadcast to room (exclude_self=False because sender is via HTTP)
        socketio.emit(event, data, room=room)
        logger.info(f"Relayed HTTP message to room {room}: event={event}")
        
        return jsonify({'status': 'ok'}), 200

    except Exception as e:
        logger.error(f"Relay error: {e}")
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    # use_reloader=False to prevent double execution and potential SSL issues on startup
    socketio.run(app, host='0.0.0.0', port=5000, debug=True, use_reloader=False)
