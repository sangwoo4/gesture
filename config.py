import os
import firebase_admin
from firebase_admin import credentials, storage
from dotenv import load_dotenv

# 환경 변수 로드
load_dotenv()

# 환경 변수에서 Firebase 인증 정보 가져오기
firebase_credentials_path = os.getenv("FIREBASE_CREDENTIALS")
firebase_storage_bucket = os.getenv("FIREBASE_STORAGE_BUCKET")

# Firebase 인증 JSON 파일이 존재하는지 확인
if not firebase_credentials_path or not os.path.exists(firebase_credentials_path):
    raise FileNotFoundError(f"Firebase 인증 파일이 없습니다: {firebase_credentials_path}")

# Firebase 초기화 (이미 초기화되지 않았다면)
if not firebase_admin._apps:
    cred = credentials.Certificate(firebase_credentials_path)
    firebase_admin.initialize_app(cred, {"storageBucket": firebase_storage_bucket})

# Firebase 스토리지 버킷 가져오기
bucket = storage.bucket()
print(f"✅ Firebase 연결됨: {bucket.name}")