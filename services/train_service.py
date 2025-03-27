from sqlalchemy.orm import Session
from models import File
from fastapi import HTTPException
import pandas as pd
import os
from sklearn.model_selection import train_test_split
import time
import numpy as np
import tensorflow as tf
from sklearn.preprocessing import LabelEncoder
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Dense
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping

from services.firebase_service import get_cached_or_download, list_files_in_firebase

CACHE_DIR = "./downloads"
BASE_DIR = "./models/"


def get_model_info(model_code: str, db: Session) -> File:
    model_info = db.query(File).filter(File.id == model_code).first()

    if not model_info:
        raise HTTPException(status_code=404, detail="Model not found")

    return model_info

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


def new_convert_to_npy() -> tuple[np.ndarray, np.ndarray]:
    CSV_PATH = "/Users/park/Desktop/project/2025_capston/fastapi_project_1/models/basic_hand_landmarks.csv"

    # ✅ CSV 데이터 불러오기
    CSV_DATA = pd.read_csv(CSV_PATH)

    # ✅ CSV 데이터 변환
    labels = CSV_DATA["label"].to_numpy()  # 라벨 컬럼 분리
    features = CSV_DATA.drop(columns=["label"]).to_numpy()  # 랜드마크 데이터
    DATA = np.hstack((features, labels.reshape(-1, 1)))

    # 데이터 분리 (랜드마크 / 라벨)
    X = np.array([row[:-1].astype(np.float32) for row in DATA])
    y = np.array([row[-1] for row in DATA])

    # 데이터셋 분할 (8 대 2)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    # 분할된 데이터를 변수로 저장
    TRAIN_DATA_UPDATE = np.column_stack((X_train, y_train))
    TEST_DATA_UPDATE = np.column_stack((X_test, y_test))

    return TRAIN_DATA_UPDATE, TEST_DATA_UPDATE



def train_new_model_service(model_code: str, landmarks: list, db: Session) -> str:

    model_info = get_model_info(model_code, db)
    basic_model_path, BASIC_TRAIN_DATA, BASIC_TEST_DATA = download_model(model_info)
    UPDATE_TRAIN_DATA, UPDATE_TEST_DATA = new_convert_to_npy()

    X_basic_train = BASIC_TRAIN_DATA[:, :-1].astype(np.float32)
    X_basic_test = BASIC_TEST_DATA[:, :-1].astype(np.float32)
    X_update_train = UPDATE_TRAIN_DATA[:, :-1].astype(np.float32)
    X_update_test = UPDATE_TEST_DATA[:, :-1].astype(np.float32)

    # y_basic_train = BASIC_TRAIN_DATA[:, -1].astype(np.float32)
    # y_basic_test = BASIC_TEST_DATA[:, -1]
    # y_update_train = UPDATE_TRAIN_DATA[:, -1].astype(np.float32)
    # y_update_test = UPDATE_TEST_DATA[:, -1]
    label_encoder = LabelEncoder()

    y_basic_train = label_encoder.fit_transform(BASIC_TRAIN_DATA[:, -1])
    y_update_train = label_encoder.transform(UPDATE_TRAIN_DATA[:, -1])

    y_basic_test = label_encoder.transform(BASIC_TEST_DATA[:, -1])
    y_update_test = label_encoder.transform(UPDATE_TEST_DATA[:, -1])



    def get_unique_labels(existing_labels, new_labels):
        # 기존 라벨이 문자열이면 그대로 사용, 숫자라면 문자열로 변환하여 set 생성
        existing_labels = np.array(existing_labels)

        # 기존 라벨 매핑 생성
        unique_existing_labels = sorted(set(existing_labels))  # 정렬하여 일정한 인덱스 유지
        label_mapping = {label: idx for idx, label in enumerate(unique_existing_labels)}

        # 새로운 라벨을 숫자로 변환
        new_labels_mapped = []
        max_label = len(label_mapping)  # 새로운 라벨 추가할 때 사용할 인덱스

        for label in new_labels:
            label = str(label)  # 문자열 변환 후 비교
            if label not in label_mapping:
                # 새로운 라벨이면 매핑 추가
                label_mapping[label] = max_label
                max_label += 1
            new_labels_mapped.append(label_mapping[label])

        return np.array(new_labels_mapped), label_mapping

    # 기존 라벨과 중복된 라벨이 있는지 확인하고, UPDATE 데이터의 라벨 변경
    y_update_train, _ = get_unique_labels(y_basic_train, y_update_train)
    y_update_test, _ = get_unique_labels(y_basic_test, y_update_test)

    print("중복 라벨 검출 및 변경 완료.")

    # 중복 검사 함수 (랜드마크 숫자 그대로 비교)
    def calculate_duplicate_ratio(X_existing, X_new):
        duplicate_count = 0
        total_new_count = len(X_new)

        for landmark in X_new:
            if any(np.allclose(landmark, existing) for existing in X_existing):
                duplicate_count += 1

        duplicate_ratio = (duplicate_count / total_new_count) * 100 if total_new_count > 0 else 0
        return duplicate_ratio

    # 기존 데이터와 새로운 데이터 간 중복 비율 계산
    train_duplicate_ratio = calculate_duplicate_ratio(X_basic_train, X_update_train)
    test_duplicate_ratio = calculate_duplicate_ratio(X_basic_test, X_update_test)

    # 평균 중복 비율 계산
    avg_duplicate_ratio = (train_duplicate_ratio + test_duplicate_ratio) / 2
    print(f"기존 데이터와 새로운 데이터 간 중복 비율: {avg_duplicate_ratio:.2f}%")

    # 95% 이상 중복되면 학습 중단
    if avg_duplicate_ratio >= 95:
        print("중복된 데이터가 95% 이상입니다. 전이 학습을 중단합니다.")
        exit(1)

    print("새로운 데이터가 충분히 포함됨. 학습을 계속 진행합니다.")

    # 데이터 병합
    X_train = np.concatenate((X_basic_train, X_update_train), axis=0)
    X_test = np.concatenate((X_basic_test, X_update_test), axis=0)
    y_train = np.concatenate((y_basic_train, y_update_train), axis=0)
    y_test = np.concatenate((y_basic_test, y_update_test), axis=0)

    print("y_train shape:", y_train.shape)
    print("y_test shape:", y_test.shape)

    # 최종 데이터
    COMBINE_TRAIN_DATA = np.column_stack((X_train, y_train))
    COMBINE_TEST_DATA = np.column_stack((X_test, y_test))

    # 원-핫 인코딩 변환
    num_classes = len(set(y_train))
    y_train = to_categorical(y_train, num_classes=num_classes)
    y_test = to_categorical(y_test, num_classes=num_classes)

    # 데이터 변환 (Conv1D 입력 형태로 맞춤)
    X_train = X_train.reshape(-1, X_train.shape[1], 1)
    X_test = X_test.reshape(-1, X_test.shape[1], 1)

    # 기존 모델 불러오기 (변수에서 로드)
    base_model = load_model(basic_model_path)

    # 로드 확인
    print(base_model.summary())

    # 기존 모델의 레이어 개수 확인
    layer_index = len(base_model.layers)

    # 기존 모델의 출력층을 제거하고 새로운 출력층 추가
    new_model = Sequential()
    for layer in base_model.layers[:-1]:
        layer.trainable = False
        new_model.add(layer)

    # 새로운 출력층 추가
    new_model.add(Dense(64, activation='relu', kernel_initializer='he_normal', name=f"dynamic_dense_{layer_index}"))
    new_model.add(Dense(num_classes, activation='softmax', name=f"dynamic_output_{layer_index + 1}"))

    # 모델 컴파일
    new_model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.0005),
                      loss='categorical_crossentropy',
                      metrics=['accuracy'])

    # Early Stopping 설정
    early_stopping = EarlyStopping(monitor='val_accuracy', patience=10, restore_best_weights=True)

    # 모델 학습
    start_time = time.time()
    new_model.fit(X_train, y_train, epochs=1000, batch_size=32, validation_data=(X_test, y_test),
                  callbacks=[early_stopping])
    end_time = time.time()

    # 모델 평가
    loss, acc = new_model.evaluate(X_test, y_test, verbose=0)
    print(f"모델 학습 완료 - 정확도: {acc:.4f}, 손실: {loss:.4f}, 학습 시간: {end_time - start_time:.2f} 초")

    # 학습된 모델을 변수로 저장
    H5_MODEL = new_model

    # TFLite 변환
    converter = tf.lite.TFLiteConverter.from_keras_model(H5_MODEL)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    TFLITE_MODEL = converter.convert()

    print("TFLite 모델 변환 완료!")

    # 반환할 데이터
    RESULTS = {
        "h5_model": H5_MODEL,  # 학습된 Keras 모델
        "tflite_model": TFLITE_MODEL,  # 변환된 TFLite 모델
        "combine_train_data": COMBINE_TRAIN_DATA,  # 통합된 학습 데이터
        "combine_test_data": COMBINE_TEST_DATA  # 통합된 테스트 데이터
    }

    return model_info