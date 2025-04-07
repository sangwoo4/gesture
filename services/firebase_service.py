import os
import time
from config import bucket

CACHE_DIR = "./basic_models"
CACHE_EXPIRATION = 24 * 60 * 60

def is_cached(file_path: str) -> bool:
    if not os.path.exists(file_path):
        return False

    file_mtime = os.path.getmtime(file_path)
    if time.time() - file_mtime > CACHE_EXPIRATION:
        os.remove(file_path)
        return False

    return True


def get_cached_or_download(file_name: str, firebase_path: str) -> str:
    local_path = os.path.join(CACHE_DIR, file_name)
    print(local_path)
    os.makedirs(os.path.dirname(local_path), exist_ok=True)

    if not is_cached(local_path):  # 캐시가 없거나 만료된 경우
        print(f"Downloading {file_name} from Firebase...")
        download_from_firebase(firebase_path, local_path)
    else:
        print(f"Using cached {file_name} from {local_path}")

    return local_path

def download_from_firebase(firebase_path: str, local_path: str):
    blob = bucket.blob(firebase_path)

    print(f"Downloading from Firebase: {firebase_path} → {local_path}")  # 디버깅용 로그 추가
    blob.download_to_filename(local_path)

    print(f"Downloaded {firebase_path} to {local_path}")

def list_files_in_firebase():
    blobs = bucket.list_blobs()
    file_list = [blob.name for blob in blobs]
    print("Firebase Storage에 있는 파일 목록:", file_list)

def upload_model_to_firebase(local_model_path: str, model_name: str) -> str:
    blob = bucket.blob(f"basic_models/{model_name}")
    blob.upload_from_filename(local_model_path)
    blob.make_public()
    return blob.public_url


