from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session
#from database import get_db
import time
from app.services.convert_services import convert_landmarks_to_csv
from app.services.update_moddel_service import train_new_model_service

router = APIRouter()

class TrainData(BaseModel):
    model_code: str
    gesture: str
    landmarks: list

@router.post("/train_model/")
async def train_model(request: TrainData):
    start = time.time()
    model_code = request.model_code
    gesture = request.gesture
    landmarks = request.landmarks

    csv_path = convert_landmarks_to_csv(landmarks, gesture)
    #csv_path = "app/cache_dir/update_hand_landmarks.csv"
    new_model_code, new_tflite_model_url = await train_new_model_service(model_code, csv_path)
    end = time.time()
    print(f"총시간={end - start:.2f}초")

    return {
        "new_model_code": new_model_code,
        "new_tflite_model_url": new_tflite_model_url
    }
