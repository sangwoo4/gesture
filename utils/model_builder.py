import numpy as np
import pandas as pd

def new_convert_to_npy(csv_path: str) -> np.ndarray:
    CSV_PATH = csv_path

    print(f"[로컬 모드] CSV 파일 로드 중: {CSV_PATH}")
    server_df = pd.read_csv(CSV_PATH)
    server_labels = server_df["label"].to_numpy(dtype=str)
    server_features = server_df.drop(columns=["label"]).to_numpy(dtype=np.float32)
    server_np_data = np.hstack((server_features, server_labels.reshape(-1, 1)))
    NPY_DATA = server_np_data  # 저장하지 않고 변수로만 유지

    return NPY_DATA

