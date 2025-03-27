from fastapi import APIRouter
from fastapi.responses import FileResponse
from services.model_service import get_existing_model

router = APIRouter()

@router.get("/download_model/{model_name}")
async def download_model(model_name: str):
    model_path = get_existing_model(model_name)
    return {"model_path": model_path}

@router.get("/get_model/{model_name}")
async def get_model(model_name: str):
    model_path = get_existing_model(model_name)
    return FileResponse(model_path, media_type="application/octet-stream")

