from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from database import get_db
from services.convert_services import convert_landmarks_to_csv
from services.update_moddel_service import train_new_model_service
import time
router = APIRouter()

class TrainData(BaseModel):
    model_code: str
    gesture: str
    landmarks: list

@router.post("/train_model/")
async def train_model(request: TrainData, db: Session = Depends(get_db)):
    start = time.time()
    model_code = request.model_code
    gesture = request.gesture
    landmarks = request.landmarks
    csv_path = convert_landmarks_to_csv(landmarks, gesture)
    new_model_code = await train_new_model_service(model_code, csv_path, db)
    end = time.time()
    print(f"총시간={end - start:.2f}초")

    return {"new_model_code": new_model_code}
