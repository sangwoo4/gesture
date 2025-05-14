from fastapi import APIRouter
from fastapi.responses import FileResponse
from services.model_service import get_existing_model

router = APIRouter()

@router.get("/download_model/{model_name}")
async def download_model(model_name: str):
    BASE_MODEL_PATH = get_existing_model(model_name)
    return {"BASE_MODEL_PATH": BASE_MODEL_PATH}

@router.get("/get_model/{model_name}")
async def get_model(model_name: str):
    BASE_MODEL_PATH = get_existing_model(model_name)
    return FileResponse(BASE_MODEL_PATH, media_type="application/octet-stream")

