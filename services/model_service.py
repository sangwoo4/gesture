import os

MODELS_CACHE_DIR = "./models_cache"
os.makedirs(MODELS_CACHE_DIR, exist_ok=True)

def get_model_path(model_name: str) -> str:
    return os.path.join(MODELS_CACHE_DIR, model_name)

def get_existing_model(model_name: str) -> str:
    local_model_path = get_model_path(model_name)

    if not os.path.exists(local_model_path):
        print(f"{model_name} Firebase downloading...")

    return local_model_path