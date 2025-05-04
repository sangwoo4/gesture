import asyncio
import uuid
from concurrent.futures import ThreadPoolExecutor

from fastapi import HTTPException
from sqlalchemy.orm import Session
from models import File
from services.firebase_service import get_cached_or_download
import os
import numpy as np

executor = ThreadPoolExecutor(max_workers=10)

def get_model_info(model_code: str, db: Session) -> File:
    model_info = db.query(File).filter(File.id == model_code).first()
    if not model_info:
        raise HTTPException(status_code=404, detail="Model not found")

    return model_info

# def download_model(model_info: File) -> tuple[str, np.ndarray, np.ndarray]:
#     #os.makedirs(CACHE_DIR, exist_ok=True)
#
#     # 모델 파일 다운로드
#     basic_model_path = get_cached_or_download(model_info.Model, f"{model_info.Model}")
#     # 학습 데이터 다운로드
#     basic_train_path = get_cached_or_download(model_info.Train_Data, f"{model_info.Train_Data}")
#     basic_test_path = get_cached_or_download(model_info.Test_Data, f"{model_info.Test_Data}")
#
#     # ✅ Firebase에서 받은 데이터만 사용하도록 변경
#     BASIC_TRAIN_DATA = np.load(basic_train_path, allow_pickle=True)
#     BASIC_TEST_DATA = np.load(basic_test_path, allow_pickle=True)
#
#     return basic_model_path, BASIC_TRAIN_DATA, BASIC_TEST_DATA

# ⬇️ 비동기 병렬 다운로드를 위한 래퍼 함수
import asyncio

async def download_model(model_info: File):
    loop = asyncio.get_event_loop()
    await asyncio.gather(
        loop.run_in_executor(executor, get_cached_or_download, model_info.Model, model_info.Model),
        loop.run_in_executor(executor, get_cached_or_download, model_info.Train_Data, model_info.Train_Data),
        loop.run_in_executor(executor, get_cached_or_download, model_info.Test_Data, model_info.Test_Data),
    )


def save_model_info(
        db: Session,
        new_model_code: str,
        updated_train_name: str,
        updated_test_name: str,
        updated_model_name: str) -> None:

    new_model_info = File(
        code = new_model_code,
        Model=updated_model_name,
        Train_Data=updated_train_name,
        Test_Data=updated_test_name,
    )
    db.add(new_model_info)
    db.commit()

