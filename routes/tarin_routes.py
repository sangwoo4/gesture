from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from database import get_db
from services.update_moddel_service import train_new_model_service
router = APIRouter()

class TrainData(BaseModel):
    model_code: str
    gesture: str
    landmarks: list

@router.post("/train_model/")
async def train_model(request: TrainData, db: Session = Depends(get_db)):
    model_code = request.model_code
    gesture = request.gesture
    landmarks = request.landmarks
    new_model_code = train_new_model_service(model_code, landmarks, db, gesture)
    return {"new_model_code": new_model_code}
