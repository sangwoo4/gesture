import os

MODELS_CACHE_DIR = "./models_cache"
os.makedirs(MODELS_CACHE_DIR, exist_ok=True)

def get_BASE_MODEL_PATH(model_name: str) -> str:
    return os.path.join(MODELS_CACHE_DIR, model_name)

def get_existing_model(model_name: str) -> str:
    local_BASE_MODEL_PATH = get_BASE_MODEL_PATH(model_name)

    if not os.path.exists(local_BASE_MODEL_PATH):
        print(f"{model_name} Firebase downloading...")

    return local_BASE_MODEL_PATH