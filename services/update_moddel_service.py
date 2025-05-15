import tensorflow as tf
import numpy as np
import os
import json
import time
import random

from collections import Counter

from config import BASE_DIR, NEW_DIR

from utils.model_io import get_model_info, download_model
from utils.preprocessing import generate_model_filename, new_split_landmarks, find_duplicate_label_pairs_by_distance
from utils.model_builder import new_convert_to_npy
from services.firebase_service import upload_model_to_firebase

from sqlalchemy.orm import Session
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Dense, Flatten, Dropout
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping
from sklearn.utils import class_weight


def train_new_model_service(model_code: str, landmarks: list, db: Session, gesture: str) -> str:
    model_file_name = generate_model_filename(prefix=gesture)

    updated_train_data_name = f"update_{model_file_name}_train_hand_landmarks.npy"
    updated_test_data_name = f"update_{model_file_name}_test_hand_landmarks.npy"
    updated_model_name = f"update_{model_file_name}_model_cnn.h5"
    updated_tflite_name = f"update_{model_file_name}_cnn.tflite"

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

    # ✅ 라벨 매핑 처리
    label_to_index = {"none": 0}  # 항상 0으로 고정
    existing_labels = sorted(set(y_basic_train) | set(y_basic_test) - {"none"})
    update_labels = sorted(set(y_update_train) | set(y_update_test) - {"none"})

    # 기존 라벨 인덱스 부여 (1부터 시작)
    for idx, label in enumerate(existing_labels, start=1):
        label_to_index[label] = idx

    # 업데이트 라벨은 기존 max 인덱스 이후부터 부여
    start_idx = max(label_to_index.values()) + 1
    for idx, label in enumerate(update_labels, start=start_idx):
        if label not in label_to_index:  # 중복 방지
            label_to_index[label] = idx

    # 역매핑
    index_to_label = {idx: label for label, idx in label_to_index.items()}

    # ✅ 디버깅 출력
    print("\n✅ 라벨 매핑 (label_to_index):")
    for label, idx in label_to_index.items():
        print(f"  '{label}' → {idx}")

    print("\n✅ 라벨 매핑 (index_to_label):")
    for idx, label in index_to_label.items():
        print(f"  {idx} → '{label}'")

    X_train_filtered = []
    y_train_filtered = []
    for x, label in zip(X_train, y_train):
        if label != "none":
            X_train_filtered.append(x)
            y_train_filtered.append(label_to_index[label])

    num_classes = max(label_to_index.values()) + 1
    X_train = np.array(X_train_filtered)
    y_train = to_categorical(y_train_filtered, num_classes=num_classes)

    y_test_idx = [label_to_index[label] for label in y_test]
    y_test = to_categorical(y_test_idx, num_classes=num_classes)

    unique_labels = sorted(set(y_update_train.tolist() + y_update_test.tolist()))
    added_label_to_index = {label: label_to_index[label] for label in unique_labels}
    added_index_to_label = {idx: label for label, idx in added_label_to_index.items()}

    data_int = json.dumps(sorted(added_index_to_label.keys()))
    
    # Conv2D 입력으로 변경
    X_train = X_train[:, :63].reshape(-1, 21, 3, 1)
    X_test = X_test[:, :63].reshape(-1, 21, 3, 1)

    # 동적 라벨 기반 이름 생성
    label_ids = sorted(label_to_index.values())
    label_str = "_".join(map(str, label_ids))
    dense_name = f"dense_cls_{label_str}"
    dropout_name = f"dropout_cls_{label_str}"
    output_name = f"output_cls_{label_str}"

    new_model = Sequential()
    for layer in USER_MODEL.layers:
        layer.trainable = False
        new_model.add(layer)
        if isinstance(layer, Flatten):
            break

    new_model.add(Dense(128, activation='relu', kernel_initializer='he_normal', name=dense_name + "_1"))
    new_model.add(Dropout(0.4, name=dropout_name + "_1"))

    new_model.add(Dense(64, activation='relu', kernel_initializer='he_normal', name=dense_name + "_2"))
    new_model.add(Dropout(0.3, name=dropout_name + "_2"))

    new_model.add(Dense(num_classes, activation='softmax', name=output_name))

    new_model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.0001),
                      loss='categorical_crossentropy',
                      metrics=['accuracy'])

    # 학습 시작
    early_stopping = EarlyStopping(monitor='val_loss', patience=10, restore_best_weights=True)

    # 클래스 불균형 완화
    y_train_idx = np.argmax(y_train, axis=1)
    classes_used = np.unique(y_train_idx)
    class_weights = class_weight.compute_class_weight(
        class_weight='balanced',
        classes=classes_used,
        y=y_train_idx
    )
    class_weight_dict = {i: class_weights[list(classes_used).index(i)] if i in classes_used else 0.0 for i in range(num_classes)}

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

        # 더 큰 범위의 샘플을 제공
    def representative_dataset():
        indices = list(range(len(X_train)))
        random.shuffle(indices)
        for i in indices[:500]:
            yield [X_train[i:i+1].astype(np.float32)]

    new_model.save(UPDATED_MODEL_PATH)
    converter = tf.lite.TFLiteConverter.from_keras_model(new_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8

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
