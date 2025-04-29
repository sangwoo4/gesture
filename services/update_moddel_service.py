import tensorflow as tf
import numpy as np
import os
import json
import time

from collections import Counter

from config import BASE_DIR, NEW_DIR

from utils.model_io import get_model_info, download_model
from utils.preprocessing import generate_model_filename, new_split_landmarks, find_duplicate_label_pairs_by_distance
from utils.model_builder import new_convert_to_npy
from services.firebase_service import upload_model_to_firebase

from sqlalchemy.orm import Session
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Dense, Conv2D, Flatten
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping
from sklearn.utils import class_weight



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

    pairs_train = find_duplicate_label_pairs_by_distance(X_basic_train, y_basic_train, X_update_train, y_update_train)
    pairs_test = find_duplicate_label_pairs_by_distance(X_basic_test, y_basic_test, X_update_test, y_update_test)

    train_total = len(X_update_train)
    test_total = len(X_update_test)
    train_ratio = (len(pairs_train) / train_total) * 100 if train_total else 0
    test_ratio = (len(pairs_test) / test_total) * 100 if test_total else 0

    # 라벨 별 빈도수 집계
    counter_train = Counter(pairs_train)
    counter_test = Counter(pairs_test)

    print("\n[중복 검사 요약]")
    print(f"Train 중복률: {train_ratio:.2f}%")
    print(f"Test 중복률: {test_ratio:.2f}%")

    print("\n[Train 중복 라벨 쌍]")
    for (new_label, old_label), count in counter_train.items():
        ratio = (count / train_total) * 100
        print(f"{new_label} (업데이트) <-> {old_label} (기존) x {count}회 (비율: {ratio:.2f}%)")

    print("\n[Test 중복 라벨 쌍]")
    for (new_label, old_label), count in counter_test.items():
        ratio = (count / test_total) * 100
        print(f"{new_label} (업데이트) <-> {old_label} (기존) x {count}회 (비율: {ratio:.2f}%)")

    # 70% 이상이면 학습 중단
    if train_ratio >= 70 or test_ratio >= 70:
        exit(1)

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

    # 이 부분 추가 함
    data_int = json.dumps(sorted(added_index_to_label.keys()))

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
        layer.trainable = False
        new_model.add(layer)
        if isinstance(layer, Flatten):
            break

    # Dense 레이어 개선
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
        batch_size=16,
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
    print("추가 데이터", data_int)
