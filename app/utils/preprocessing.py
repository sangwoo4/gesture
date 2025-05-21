import os
from typing import Any

import numpy as np
from numpy import ndarray, dtype

from sklearn.model_selection import train_test_split

def generate_model_filename(prefix="gesture"):
    import time, uuid
    timestamp = int(time.time())
    uid = uuid.uuid4().hex[:8]
    return f"{timestamp}_{uid}"



def new_split_landmarks(NPY_DATA: np.ndarray) -> tuple[
    ndarray[Any, dtype[Any]], ndarray[Any, dtype[Any]]]:
    data = NPY_DATA.copy()

    X = np.array([row[:-1].astype(np.float32) for row in data])
    y = np.array([row[-1] for row in data], dtype=str)

    # 데이터셋 분할 (8 대 2)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    TRAIN_DATA_UPDATE = np.column_stack((X_train, y_train))
    TEST_DATA_UPDATE = np.column_stack((X_test, y_test))


    print("Train Data saving complete!")

    print("데이터 분할 완료!")
    print(f"Train 데이터 크기: {X_train.shape[0]}")
    print(f"Test 데이터 크기: {X_test.shape[0]}")

    return TRAIN_DATA_UPDATE, TEST_DATA_UPDATE


def find_duplicate_label_pairs_by_distance(
    X_existing,
    y_existing,
    X_new,
    y_new,
    threshold: float = 0.02
) -> list[tuple[str, str]]:
    duplicate_pairs = []

    for x_new, label_new in zip(X_new, y_new):
        for x_exist, label_exist in zip(X_existing, y_existing):
            distance = np.linalg.norm(x_new - x_exist)
            if distance < threshold:
                duplicate_pairs.append((str(label_new), str(label_exist)))
                break

    return duplicate_pairs