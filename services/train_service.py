from sqlalchemy.orm import Session
from models import File
from fastapi import HTTPException
import pandas as pd
import os
from sklearn.model_selection import train_test_split
import time
import numpy as np
import tensorflow as tf
import json
from services.firebase_service import upload_model_to_firebase

from config import BASE_DIR, NEW_DIR
from collections import Counter
from sklearn.preprocessing import LabelEncoder
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Dense, Conv2D, Flatten
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping
from sklearn.utils import class_weight

from services.firebase_service import get_cached_or_download

CACHE_DIR = "./downloads"



def get_model_info(model_code: str, db: Session) -> File:
    model_info = db.query(File).filter(File.id == model_code).first()
    if not model_info:
        raise HTTPException(status_code=404, detail="Model not found")

    return model_info

def generate_model_filename(prefix="gesture"):
    import time, uuid
    timestamp = int(time.time())
    uid = uuid.uuid4().hex[:8]
    return f"{prefix}_{timestamp}_{uid}"

def download_model(model_info: File) -> tuple[str, np.ndarray, np.ndarray]:
    os.makedirs(CACHE_DIR, exist_ok=True)

    # 모델 파일 다운로드
    basic_model_path = get_cached_or_download(model_info.Model, f"{model_info.Model}")

    # 학습 데이터 다운로드
    basic_train_path = get_cached_or_download(model_info.Train_Data, f"{model_info.Train_Data}")
    basic_test_path = get_cached_or_download(model_info.Test_Data, f"{model_info.Test_Data}")

    # ✅ Firebase에서 받은 데이터만 사용하도록 변경
    BASIC_TRAIN_DATA = np.load(basic_train_path, allow_pickle=True)
    BASIC_TEST_DATA = np.load(basic_test_path, allow_pickle=True)

    return basic_model_path, BASIC_TRAIN_DATA, BASIC_TEST_DATA


def new_convert_to_npy() -> np.ndarray:
    CSV_PATH = "/Users/park/Desktop/project/2025_capston/fastapi_project_1/basic_models/update_hand_landmarks.csv"

    print(f"[로컬 모드] CSV 파일 로드 중: {CSV_PATH}")
    server_df = pd.read_csv(CSV_PATH)
    server_labels = server_df["label"].to_numpy(dtype=str)
    server_features = server_df.drop(columns=["label"]).to_numpy(dtype=np.float32)
    server_np_data = np.hstack((server_features, server_labels.reshape(-1, 1)))
    NPY_DATA = server_np_data  # 저장하지 않고 변수로만 유지

    return NPY_DATA

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


def train_new_model_service(model_code: str, landmarks: list, db: Session, gesture: str) -> str:

    model_file_name = generate_model_filename(prefix=gesture)

    updated_train_data_name = f"update_{model_file_name}_train_hand_landmarks.npy"
    updated_test_data_name = f"update_{model_file_name}_test_hand_landmarks.npy"
    updated_model_name = f"update_{model_file_name}_model_cnn.h5"
    updated_tflite_name = f"update_{model_file_name}_cnn.tflite"
    updated_label_map_name = f"update_{model_file_name}_label_index_map.json"

    model_info = get_model_info(model_code, db)
    download_model(model_info)
    NPY_DATA = new_convert_to_npy()

    BASIC_TRAIN_DATA = np.load(os.path.join(BASE_DIR, model_info.Train_Data), allow_pickle=True)
    BASIC_TEST_DATA = np.load(os.path.join(BASE_DIR, model_info.Test_Data), allow_pickle=True)
    BASE_MODEL_PATH = os.path.join(BASE_DIR, model_info.Model)
    USER_MODEL = load_model(BASE_MODEL_PATH)

    UPDATE_TRAIN_DATA_PATH, UPDATE_TEST_DATA_PATH = new_split_landmarks(NPY_DATA, updated_train_data_name, updated_test_data_name)
    UPDATE_TRAIN_DATA = np.load(os.path.join(NEW_DIR, UPDATE_TRAIN_DATA_PATH), allow_pickle=True)
    UPDATE_TEST_DATA = np.load(os.path.join(NEW_DIR, UPDATE_TEST_DATA_PATH), allow_pickle=True)

    UPDATE_LABEL_INDEX_MAP = os.path.join(BASE_DIR, updated_label_map_name)
    UPDATED_MODEL_PATH = os.path.join(NEW_DIR, updated_model_name)
    UPDATED_TFLITE_PATH = os.path.join(NEW_DIR, updated_tflite_name)


    X_basic_train = BASIC_TRAIN_DATA[:, :-1].astype(np.float32)
    y_basic_train = BASIC_TRAIN_DATA[:, -1].astype(str)
    X_basic_test = BASIC_TEST_DATA[:, :-1].astype(np.float32)
    y_basic_test = BASIC_TEST_DATA[:, -1].astype(str)

    X_update_train = UPDATE_TRAIN_DATA[:, :-1].astype(np.float32)
    y_update_train = UPDATE_TRAIN_DATA[:, -1].astype(str)
    X_update_test = UPDATE_TEST_DATA[:, :-1].astype(np.float32)
    y_update_test = UPDATE_TEST_DATA[:, -1].astype(str)

    def calculate_duplicate_ratio(X_existing, y_existing, X_new, y_new):
        unique_classes = sorted(set(y_new))
        class_duplicate_ratios = {}
        for cls in unique_classes:
            X_existing_cls = X_existing[y_existing == cls]
            X_new_cls = X_new[y_new == cls]
            duplicate_count = sum(
                any(np.allclose(landmark, existing) for existing in X_existing_cls)
                for landmark in X_new_cls
            )
            ratio = (duplicate_count / len(X_new_cls) * 100) if len(X_new_cls) > 0 else 0
            class_duplicate_ratios[cls] = ratio
        return class_duplicate_ratios

    train_dup_ratios = calculate_duplicate_ratio(X_basic_train, y_basic_train, X_update_train, y_update_train)
    test_dup_ratios = calculate_duplicate_ratio(X_basic_test, y_basic_test, X_update_test, y_update_test)

    for cls, ratio in train_dup_ratios.items():
        print(f"클래스 {cls} 학습 중복: {ratio:.2f}%")
    for cls, ratio in test_dup_ratios.items():
        print(f"클래스 {cls} 테스트 중복: {ratio:.2f}%")

    if any(r >= 30 for r in train_dup_ratios.values()) or any(r >= 30 for r in test_dup_ratios.values()):
        print("중복 비율이 높은 클래스가 있어 학습을 중단합니다.")
        exit(1)

    # 병합 및 라벨 인코딩
    X_train = np.concatenate((X_basic_train, X_update_train), axis=0)
    X_test = np.concatenate((X_basic_test, X_update_test), axis=0)
    y_train = np.concatenate((y_basic_train, y_update_train), axis=0)
    y_test = np.concatenate((y_basic_test, y_update_test), axis=0)

    # 라벨 인덱스 생성
    label_to_index = {}
    index_to_label = {}

    # 1. 기존 라벨 먼저 등록
    for label in list(y_basic_train) + list(y_basic_test):
        if label not in label_to_index:
            idx = len(label_to_index)
            label_to_index[label] = idx
            index_to_label[idx] = label

    # 2. 새로운 라벨 등록
    for label in list(y_update_train) + list(y_update_test):
        if label not in label_to_index:
            idx = len(label_to_index)
            label_to_index[label] = idx
            index_to_label[idx] = label

    # JSON 저장 시에는 추가된 라벨만 추려서 저장
    unique_labels = sorted(set(y_update_train.tolist() + y_update_test.tolist()))
    added_label_to_index = {label: label_to_index[label] for label in unique_labels}
    added_index_to_label = {idx: label for label, idx in added_label_to_index.items()}

    with open(UPDATE_LABEL_INDEX_MAP, "w") as f:
        json.dump(added_index_to_label, f)
    print(f"✅ 추가 학습 라벨만 저장 완료: {UPDATE_LABEL_INDEX_MAP}")

    # 전체 라벨 매핑을 사용하는 코드로 수정
    y_train = to_categorical([label_to_index[l] for l in y_train], num_classes=len(label_to_index))
    y_test = to_categorical([label_to_index[l] for l in y_test], num_classes=len(label_to_index))

    # 입력 형태 변환
    X_train = X_train[:, :63].reshape(-1, 21, 3, 1)
    X_test = X_test[:, :63].reshape(-1, 21, 3, 1)

    # Conv2D 입력으로 변경
    X_train = X_train[:, :63].reshape(-1, 21, 3, 1)
    X_test = X_test[:, :63].reshape(-1, 21, 3, 1)

    # 동적 라벨 기반 이름 생성
    label_ids = sorted(label_to_index.values())
    label_str = "_".join(map(str, label_ids))
    dense_name = f"dense_cls_{label_str}"
    output_name = f"output_cls_{label_str}"

    # 기존: Conv2D까지만 재사용
    new_model = Sequential()
    for layer in USER_MODEL.layers:
        if isinstance(layer, Conv2D):
            layer.trainable = False
            new_model.add(layer)
        else:
            break

    # Dense 레이어 개선
    new_model.add(Flatten())
    new_model.add(Dense(64, activation='relu', kernel_initializer='he_normal', name=dense_name))
    new_model.add(Dense(len(label_to_index), activation='softmax', name=output_name))

    new_model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.0005),
                      loss='categorical_crossentropy',
                      metrics=['accuracy'])

    # 학습 시작
    early_stopping = EarlyStopping(monitor='val_loss', patience=5, restore_best_weights=True)

    # 클래스 불균형 완화
    y_train_idx = np.argmax(y_train, axis=1)
    class_weights = class_weight.compute_class_weight(
        class_weight='balanced',
        classes=np.unique(y_train_idx),
        y=y_train_idx
    )
    class_weight_dict = {i: w for i, w in enumerate(class_weights)}

    start = time.time()

    # 학습
    new_model.fit(
        X_train, y_train,
        epochs=1000,
        batch_size=32,
        validation_data=(X_test, y_test),
        callbacks=[early_stopping],
        class_weight=class_weight_dict
    )
    end = time.time()

    # 평가 및 저장
    loss, acc = new_model.evaluate(X_test, y_test, verbose=0)
    print(f"학습 완료 - acc: {acc:.4f}, loss: {loss:.4f}, 시간: {end - start:.2f}초")
    print("✅ 학습 클래스 분포:", Counter(y_train_idx))

    # 학습 세부내용
    y_pred = np.argmax(new_model.predict(X_test), axis=1)
    y_true = np.argmax(y_test, axis=1)

    used_label_indices = sorted(set(y_true))
    used_target_names = [index_to_label[i] for i in used_label_indices]

    new_model.save(UPDATED_MODEL_PATH)
    converter = tf.lite.TFLiteConverter.from_keras_model(new_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    with open(UPDATED_TFLITE_PATH, "wb") as f:
        f.write(tflite_model)
    print(f"모델 저장 완료: {UPDATED_MODEL_PATH}")
    print(f"TFLite 저장 완료: {UPDATED_TFLITE_PATH}")


    new_tflite_url = upload_model_to_firebase(UPDATE_TRAIN_DATA_PATH,
                                              UPDATE_TEST_DATA_PATH,
                                              UPDATED_MODEL_PATH,
                                              UPDATED_TFLITE_PATH)
    print(new_tflite_url)



