from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from utils.database import get_db
from services.update_moddel_service import train_new_model_service
import time

from utils.model_io import remove_folder_if_old

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

    #csv_path = convert_landmarks_to_csv(landmarks, gesture)
    cache_models_path = os.path.join("/Users/park/Desktop/project/2025_capston/fastapi_project_1/cache_dir", "models")
    remove_folder_if_old(cache_models_path)
    csv_path = "./cache_dir/models/update_hand_landmarks.csv"
    new_model_code = await train_new_model_service(model_code, csv_path, db)
    end = time.time()
    print(f"총시간={end - start:.2f}초")

    return {"new_model_code": new_model_code}
