import socketio
import time

sio = socketio.Client()

@sio.event
def connect():
    print("Debug Client Connected")
    sio.emit('join', {'room': 'debug_room', 'client_id': 'DEBUG_TESTER_01'})

@sio.event
def disconnect():
    print("Debug Client Disconnected")

try:
    sio.connect('http://kxkl.tk:5055')
    sio.wait()
except Exception as e:
    print(f"Error: {e}")
