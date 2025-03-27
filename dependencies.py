import os

MODELS_CACHE = "./models_cache"
DATA_DIR = "./data"

os.makedirs(MODELS_CACHE, exist_ok=True)
os.makedirs(DATA_DIR, exist_ok=True)

def get_model_cache(model_name: str) -> str:
    return os.path.join(MODELS_CACHE, model_name)
