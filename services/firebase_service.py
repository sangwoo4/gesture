import os
import time
from concurrent.futures import ThreadPoolExecutor
import asyncio
import numpy as np

from config import bucket
from config import BASE_DIR, NEW_DIR
CACHE_DIR = "./basic_models"
CACHE_EXPIRATION = 24 * 60 * 60

executor = ThreadPoolExecutor(max_workers=3)


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
    os.makedirs(os.path.dirname(local_path), exist_ok=True)

    # Firebase 내 경로를 new_models/ 하위로 고정
    full_firebase_path = f"new_models/{firebase_path}"

    if not is_cached(local_path):  # 캐시가 없거나 만료된 경우
        print(f"Downloading {file_name} from Firebase...")
        download_from_firebase(full_firebase_path, local_path)
    else:
        print(f"Using cached {file_name} from {local_path}")

    return local_path

def download_from_firebase(firebase_path: str, local_path: str):
    blob = bucket.blob(firebase_path)

    print(f"Downloading from Firebase: {firebase_path} → {local_path}")  # 디버깅용 로그 추가
    blob.download_to_filename(local_path)

    print(f"Downloaded {firebase_path} to {local_path}")

# def upload_model_to_firebase(
#         UPDATE_TRAIN_DATA: str,
#         UPDATE_TEST_DATA: str,
#         UPDATED_MODEL_PATH: str,
#         UPDATED_TFLITE_PATH: str,
#         firebase_folder: str = "new_models"
# ) -> str:
#     upload_files = [UPDATE_TRAIN_DATA, UPDATE_TEST_DATA, UPDATED_MODEL_PATH, UPDATED_TFLITE_PATH]
#
#     tflite_url = ""
#
#     for file_path in upload_files:
#         file_name = os.path.basename(file_path)
#         blob = bucket.blob(f"{firebase_folder}/{file_name}")
#         blob.upload_from_filename(file_path)
#         blob.make_public()
#
#         print(f"[업로드 완료] {file_name} → {blob.public_url}")
#
#         # TFLite URL만 저장
#         if file_path == UPDATED_TFLITE_PATH:
#             tflite_url = blob.public_url
#
#     return tflite_url


def upload_single_file(file_path, firebase_folder):
    file_name = os.path.basename(file_path)
    blob = bucket.blob(f"{firebase_folder}/{file_name}")
    blob.upload_from_filename(file_path)
    blob.make_public()
    print(f"[백그라운드 업로드 완료] {file_name} → {blob.public_url}")

def upload_remaining_files(other_files, firebase_folder):
    for file_path in other_files:
        upload_single_file(file_path, firebase_folder)

async def upload_model_to_firebase_async(
    UPDATE_TRAIN_DATA: str,
    UPDATE_TEST_DATA: str,
    UPDATED_MODEL_PATH: str,
    UPDATED_TFLITE_PATH: str,
    firebase_folder: str = "new_models"
) -> str:
    loop = asyncio.get_event_loop()

    # ✅ 1. 먼저 TFLite 파일만 업로드
    tflite_file_name = os.path.basename(UPDATED_TFLITE_PATH)
    tflite_blob = bucket.blob(f"{firebase_folder}/{tflite_file_name}")
    tflite_blob.upload_from_filename(UPDATED_TFLITE_PATH)
    tflite_blob.make_public()
    tflite_url = tflite_blob.public_url
    print(f"[TFLite 업로드 완료] {tflite_file_name} → {tflite_url}")

    # ✅ 2. 나머지 파일은 백그라운드에서 업로드
    other_files = [UPDATE_TRAIN_DATA, UPDATE_TEST_DATA, UPDATED_MODEL_PATH]
    loop.run_in_executor(executor, upload_remaining_files, other_files, firebase_folder)

    # ✅ 3. 클라이언트에게 TFLite URL 즉시 반환
    return tflite_url



