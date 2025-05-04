import os
import numpy as np
from config import BASE_DIR, NEW_DIR

from sklearn.model_selection import train_test_split

def generate_model_filename(prefix="gesture"):
    import time, uuid
    timestamp = int(time.time())
    uid = uuid.uuid4().hex[:8]
    return f"{prefix}_{timestamp}_{uid}"



def new_split_landmarks(NPY_DATA: np.ndarray, train_data_name: str, test_data_name: str) -> tuple[str, str]:
    data = NPY_DATA.copy()

    X = np.array([row[:-1].astype(np.float32) for row in data])
    y = np.array([row[-1] for row in data], dtype=str)

    # 데이터셋 분할 (8 대 2)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    TRAIN_DATA_UPDATE = np.column_stack((X_train, y_train))
    TEST_DATA_UPDATE = np.column_stack((X_test, y_test))
    # np.save(os.path.join(NEW_DIR, train_data_name), TRAIN_DATA_UPDATE)
    # np.save(os.path.join(NEW_DIR, test_data_name), TEST_DATA_UPDATE)

    train_path = os.path.join(NEW_DIR, train_data_name)
    test_path = os.path.join(NEW_DIR, test_data_name)
    np.save(train_path, TRAIN_DATA_UPDATE)
    np.save(test_path, TEST_DATA_UPDATE)

    print("[로컬 모드] 데이터 저장 완료!")

    print("데이터 분할 완료!")
    print(f"Train 데이터 크기: {X_train.shape[0]}")
    print(f"Test 데이터 크기: {X_test.shape[0]}")

    return train_path, test_path


def find_duplicate_label_pairs_by_distance(
    X_existing,
    y_existing,
    X_new,
    y_new,
    threshold: float = 0.02
) -> list[tuple[str, str]]:
    """
    기존 데이터와 신규 데이터 간의 유클리디안 거리 기반 중복 라벨 쌍을 반환합니다.

    Returns:
        list of tuple: (신규 라벨, 기존 라벨) 쌍의 리스트.
    """
    duplicate_pairs = []

    for x_new, label_new in zip(X_new, y_new):
        for x_exist, label_exist in zip(X_existing, y_existing):
            distance = np.linalg.norm(x_new - x_exist)
            if distance < threshold:
                duplicate_pairs.append((str(label_new), str(label_exist)))
                break

    return duplicate_pairs