import numpy as np
import pandas as pd
import os

DATA_DIR = "./data/"
MODELS_CACHE_DIR = "./models_cache/"

os.makedirs(DATA_DIR, exist_ok=True)

def convert_json_to_npy(landmarks: list) -> str:
    npy_path = os.path.join(DATA_DIR, "train_data.npy")
    data = np.array([[lm["x"], lm["y"], lm["z"]] for lm in landmarks])
    np.save(npy_path, data)
    print(f"Convert JSON to NPY Complete: {npy_path}")

    return npy_path



