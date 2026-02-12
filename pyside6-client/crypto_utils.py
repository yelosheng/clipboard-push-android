import os
import base64
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
        """
        Encrypts data using AES-GCM.
        Returns: nonce + ciphertext + tag (handled by AESGCM)
        """
        aesgcm = AESGCM(self.key)
        nonce = os.urandom(12)
        ciphertext = aesgcm.encrypt(nonce, data, None)
        return nonce + ciphertext

    def decrypt(self, encrypted_data: bytes) -> bytes:
        """
        Decrypts data using AES-GCM.
        Expects: nonce (12 bytes) + ciphertext
        """
        aesgcm = AESGCM(self.key)
        nonce = encrypted_data[:12]
        ciphertext = encrypted_data[12:]
        return aesgcm.decrypt(nonce, ciphertext, None)

    def encrypt_file(self, file_path: str) -> bytes:
        with open(file_path, 'rb') as f:
            data = f.read()
        return self.encrypt(data)

    def decrypt_to_file(self, encrypted_data: bytes, output_path: str):
        data = self.decrypt(encrypted_data)
        with open(output_path, 'wb') as f:
            f.write(data)
