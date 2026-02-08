import base64
import os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

class CryptoUtils:
    def __init__(self, key_base64=None):
        if key_base64:
            self.key = base64.b64decode(key_base64)
        else:
            self.key = AESGCM.generate_key(bit_length=256)

    def get_key_base64(self):
        return base64.b64encode(self.key).decode('utf-8')

    def encrypt(self, data: bytes) -> bytes:
        aesgcm = AESGCM(self.key)
        nonce = os.urandom(12)
        ciphertext = aesgcm.encrypt(nonce, data, None)
        return nonce + ciphertext

    def decrypt(self, encrypted_data: bytes) -> bytes:
        aesgcm = AESGCM(self.key)
        nonce = encrypted_data[:12]
        ciphertext = encrypted_data[12:]
        return aesgcm.decrypt(nonce, ciphertext, None)

def test_encryption():
    # Setup
    crypto = CryptoUtils()
    print(f"Key: {crypto.get_key_base64()}")
    
    original_text = "Hello Encrypted World! 🚀"
    print(f"Original: {original_text}")

    # Sender Logic
    try:
        encrypted_bytes = crypto.encrypt(original_text.encode('utf-8'))
        encrypted_content = base64.b64encode(encrypted_bytes).decode('utf-8')
        payload = {
            'content': encrypted_content,
            'encrypted': True
        }
        print(f"Payload: {payload}")
    except Exception as e:
        print(f"Encryption failed: {e}")
        return

    # Receiver Logic
    try:
        content = payload.get('content')
        is_encrypted = payload.get('encrypted', False)
        
        if is_encrypted:
            decoded_bytes = base64.b64decode(content)
            decrypted_bytes = crypto.decrypt(decoded_bytes)
            decrypted_text = decrypted_bytes.decode('utf-8')
            print(f"Decrypted: {decrypted_text}")
            
            assert original_text == decrypted_text
            print("✅ Verification Successful!")
        else:
            print("❌ Not encrypted")
            
    except Exception as e:
        print(f"Decryption failed: {e}")

if __name__ == "__main__":
    test_encryption()
