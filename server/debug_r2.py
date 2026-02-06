import boto3
import os
import logging
from botocore.config import Config
from dotenv import load_dotenv

# Setup minimal logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Load env
load_dotenv(r"d:\android-dev\clipboard-man\server\.env")

R2_ACCOUNT_ID = os.environ.get('R2_ACCOUNT_ID')
R2_ACCESS_KEY_ID = os.environ.get('R2_ACCESS_KEY_ID')
R2_SECRET_ACCESS_KEY = os.environ.get('R2_SECRET_ACCESS_KEY')
R2_BUCKET_NAME = os.environ.get('R2_BUCKET_NAME')

print(f"Testing R2 Connection...")
print(f"Bucket: {R2_BUCKET_NAME}")
print(f"Account ID: {R2_ACCOUNT_ID}")

# Test 1: Standard Connection
print("\n--- Test 1: Standard Connection ---")
try:
    s3 = boto3.client(
        's3',
        endpoint_url=f'https://{R2_ACCOUNT_ID}.r2.cloudflarestorage.com',
        aws_access_key_id=R2_ACCESS_KEY_ID,
        aws_secret_access_key=R2_SECRET_ACCESS_KEY,
        config=Config(signature_version='s3v4'),
        region_name='auto'
    )
    s3.head_bucket(Bucket=R2_BUCKET_NAME)
    print("✅ Test 1 Success!")
except Exception as e:
    print(f"❌ Test 1 Failed: {e}")

# Test 2: Disable SSL Verification (Dangerous but useful for debug)
print("\n--- Test 2: Verify=False ---")
try:
    s3 = boto3.client(
        's3',
        endpoint_url=f'https://{R2_ACCOUNT_ID}.r2.cloudflarestorage.com',
        aws_access_key_id=R2_ACCESS_KEY_ID,
        aws_secret_access_key=R2_SECRET_ACCESS_KEY,
        config=Config(signature_version='s3v4'),
        region_name='auto',
        verify=False
    )
    s3.head_bucket(Bucket=R2_BUCKET_NAME)
    print("✅ Test 2 Success!")
except Exception as e:
    print(f"❌ Test 2 Failed: {e}")

# Test 3: Clear Proxy Env Vars
print("\n--- Test 3: Clear Proxy & Standard ---")
old_proxies = {}
for k in ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"]:
    if k in os.environ:
        old_proxies[k] = os.environ[k]
        del os.environ[k]

try:
    s3 = boto3.client(
        's3',
        endpoint_url=f'https://{R2_ACCOUNT_ID}.r2.cloudflarestorage.com',
        aws_access_key_id=R2_ACCESS_KEY_ID,
        aws_secret_access_key=R2_SECRET_ACCESS_KEY,
        config=Config(signature_version='s3v4'),
        region_name='auto'
    )
    s3.head_bucket(Bucket=R2_BUCKET_NAME)
    print("✅ Test 3 Success!")
except Exception as e:
    print(f"❌ Test 3 Failed: {e}")
finally:
    # Restore proxies
    os.environ.update(old_proxies)
