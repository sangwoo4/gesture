from concurrent.futures import ThreadPoolExecutor

from sqlalchemy.orm import Session
#from app.models import File
from app.services.firebase_service import get_cached_or_download
import os
import zipfile
from app.utils.config import NEW_DIR, ZIP_DIR

executor = ThreadPoolExecutor(max_workers=10)

# def get_model_info(user_code: int, db: Session) -> File:
#     model_info = db.query(File).filter(File.id == user_code).first()
#     if not model_info:
#         raise HTTPException(status_code=404, detail="Model not found")
#
#     return model_info.code

# ⬇️ 비동기 병렬 다운로드를 위한 래퍼 함수
import asyncio

# async def download_model(model_info: File):
#     loop = asyncio.get_event_loop()
#     await asyncio.gather(
#         loop.run_in_executor(executor, get_cached_or_download, model_info.Model, model_info.Model),
#         loop.run_in_executor(executor, get_cached_or_download, model_info.Train_Data, model_info.Train_Data),
#         loop.run_in_executor(executor, get_cached_or_download, model_info.Test_Data, model_info.Test_Data),
#     )



async def download_model(model_info: str):
    code = model_info
    bundle_name = f"{model_info}.zip"
    zip_path = os.path.join(ZIP_DIR, bundle_name)
    unzip_path = os.path.join(NEW_DIR, code)

    # 1. 압축 해제된 모델 폴더가 이미 존재하면 다운로드 스킵
    if os.path.exists(unzip_path) and all(
        os.path.exists(os.path.join(unzip_path, fname))
        for fname in [f"{model_info}_model_cnn.h5", f"{model_info}_test_hand_landmarks.npy", f"{model_info}_train_hand_landmarks.npy"]
    ):
        print(f"[Cache Hit] Using local model files in {unzip_path}")
        return unzip_path

    print(f"[Cache Miss] Downloading model zip: {bundle_name}")

    # 2. zip 파일이 없으면 다운로드
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(executor, get_cached_or_download, bundle_name, bundle_name)

    # 3. 압축 해제
    os.makedirs(unzip_path, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zip_ref:
        zip_ref.extractall(unzip_path)

    return unzip_path


def save_model_info(
        db: Session,
        new_model_code: str,
        combined_train_name: str,
        combined_test_name: str,
        updated_model_name: str) -> None:

    new_model_info = File(
        code = new_model_code,
        Model=updated_model_name,
        Train_Data=combined_train_name,
        Test_Data=combined_test_name,
    )
    db.add(new_model_info)
    db.commit()

