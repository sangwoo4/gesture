import random
from http.client import HTTPException
from typing import Any, Coroutine

from fastapi import HTTPException
import tensorflow as tf
import numpy as np
import os

from keras.src.layers import Dropout

from app.config import NEW_DIR
from app.utils.model_io import download_model

#from app.utils.model_io import get_model_info, download_model, save_model_info
from app.utils.preprocessing import generate_model_filename, new_split_landmarks, find_duplicate_label_pairs_by_distance
from app.utils.model_builder import new_convert_to_npy
from app.services.firebase_service import upload_model_to_firebase_async

from sqlalchemy.orm import Session
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Dense, Flatten
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping
from sklearn.utils import class_weight


import asyncio
from concurrent.futures import ThreadPoolExecutor

# def prepare_datasets(model_info):
#     train_data = np.load(os.path.join(NEW_DIR/model_info, model_info.Train_Data), allow_pickle=True)
#     test_data = np.load(os.path.join(NEW_DIR, model_info.Test_Data), allow_pickle=True)
#     model_path = os.path.join(NEW_DIR/model_info, model_info.Model)
#     model = load_model(model_path)
#     return train_data, test_data, model

def prepare_datasets(model_code: str):
    train_data_path = os.path.join(NEW_DIR, model_code, f"{model_code}_train_hand_landmarks.npy")
    test_data_path = os.path.join(NEW_DIR, model_code, f"{model_code}_test_hand_landmarks.npy")
    model_path = os.path.join(NEW_DIR, model_code, f"{model_code}_model_cnn.h5")

    train_data = np.load(train_data_path, allow_pickle=True)
    test_data = np.load(test_data_path, allow_pickle=True)
    model = load_model(model_path)

    return train_data, test_data, model


def merge_datasets(basic_data, update_data):
    X1, y1 = basic_data[:, :-1].astype(np.float32), basic_data[:, -1].astype(str)
    X2, y2 = update_data[:, :-1].astype(np.float32), update_data[:, -1].astype(str)
    return np.concatenate([X1, X2]), np.concatenate([y1, y2])

def create_label_maps(y_basic_train, y_basic_test, y_update_train, y_update_test):
    label_to_index = {"none": 0}
    index_to_label = {0: "none"}

    # 기존 라벨
    existing_labels = sorted(set(y_basic_train) | set(y_basic_test) - {"none"})
    for idx, label in enumerate(existing_labels, start=1):
        label_to_index[label] = idx
        index_to_label[idx] = label

    print("existing labels: ", existing_labels)
    # 신규 라벨 중 기존에 없는 라벨 추가
    update_labels = sorted(set(y_update_train) | set(y_update_test) - {"none"})
    start_idx = max(label_to_index.values()) + 1
    for idx, label in enumerate(update_labels, start=start_idx):
        if label not in label_to_index:
            label_to_index[label] = idx
            index_to_label[idx] = label

    return label_to_index, index_to_label

def prepare_inputs(X, y, label_to_index):
    X = X[:, :63].reshape(-1, 21, 3, 1)
    y = to_categorical([label_to_index[label] for label in y], num_classes=len(label_to_index))
    return X, y


def build_transfer_model(base_model, num_classes, label_ids):
    new_model = Sequential()
    for layer in base_model.layers:
        layer.trainable = False
        new_model.add(layer)
        if isinstance(layer, Flatten):
            break

    dense_name = f"dense_cls_{'_'.join(map(str, label_ids))}"
    output_name = f"output_cls_{'_'.join(map(str, label_ids))}"
    label_str = "_".join(map(str, label_ids))
    dropout_name = f"dropout_cls_{label_str}"

    new_model.add(Dense(128, activation='relu', kernel_initializer='he_normal', name=dense_name + "_1"))
    new_model.add(Dropout(0.4, name=dropout_name + "_1"))

    new_model.add(Dense(64, activation='relu', kernel_initializer='he_normal', name=dense_name + "_2"))
    new_model.add(Dropout(0.3, name=dropout_name + "_2"))

    new_model.add(Dense(num_classes, activation='softmax', name=output_name))
    return new_model



def check_duplicates(base_data, update_data, threshold=70.0):
    pairs_train = find_duplicate_label_pairs_by_distance(
        base_data['train'][:, :-1].astype(np.float32), base_data['train'][:, -1],
        update_data['train'][:, :-1].astype(np.float32), update_data['train'][:, -1]
    )

    pairs_test = find_duplicate_label_pairs_by_distance(
        base_data['test'][:, :-1].astype(np.float32), base_data['test'][:, -1],
        update_data['test'][:, :-1].astype(np.float32), update_data['test'][:, -1]
    )
    train_ratio = (len(pairs_train) / len(update_data['train'])) * 100 if len(update_data['train']) else 0
    test_ratio = (len(pairs_test) / len(update_data['test'])) * 100 if len(update_data['test']) else 0
    if train_ratio >= threshold or test_ratio >= threshold:
        raise HTTPException(
            status_code = 400,
            detail=f"제스처 중복입니다 다른 제스처를 등록해주세요"
        )

def convert_to_tflite(model, save_path_tflite, X_train):
    def representative_dataset():
        indices = list(range(len(X_train)))
        random.shuffle(indices)
        for i in indices[:500]:
            yield [X_train[i:i + 1].astype(np.float32)]


    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8

    tflite_model = converter.convert()
    with open(save_path_tflite, 'wb') as f:
        f.write(tflite_model)


def train_model(model, X_train, y_train, X_test, y_test, class_len):
    early_stop = EarlyStopping(monitor='val_loss', patience=10, restore_best_weights=True)
    y_train_idx = np.argmax(y_train, axis=1)
    classes_used = np.unique(y_train_idx)
    class_weights = class_weight.compute_class_weight(
        'balanced',
        classes=classes_used,
        y=y_train_idx
    )
    class_weight_dict = {i: class_weights[list(classes_used).index(i)] if i in classes_used else 0.0 for i in range(class_len)}

    model.fit(
        X_train, y_train,
        epochs=1000,
        batch_size=32,
        validation_data=(X_test, y_test),
        callbacks=[early_stop],
        class_weight=class_weight_dict
    )

async def async_run_in_thread(fn, *args):
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(executor, fn, *args)

executor = ThreadPoolExecutor(max_workers=4)

async def train_new_model_service(model_code: str, csv_path: str) -> tuple[Any, str]:
    # 0. 모델 코드 생성
    new_model_code = generate_model_filename()
    updated_model_name = f"{new_model_code}_model_cnn.h5"
    updated_tflite_name = f"{new_model_code}_cnn.tflite"
    combined_train_name = f"{new_model_code}_train_hand_landmarks.npy"
    combined_test_name = f"{new_model_code}_test_hand_landmarks.npy"

    # 1. 기존 모델 정보 및 데이터 로딩
    #model_code = get_model_info(model_code, db)
    #await download_model(model_info)
    await download_model(model_code)
    basic_train, basic_test, base_model = prepare_datasets(model_code)

    # 2. 신규 CSV → NPY 변환 및 분할
    update_train, update_test = new_split_landmarks(new_convert_to_npy(csv_path))

    # 3. 중복 제거
    check_duplicates(
        base_data={'train': basic_train, 'test': basic_test},
        update_data={'train': update_train, 'test': update_test}
    )


    # 4. 전체 데이터 병합
    X_train_all, y_train_all = merge_datasets(basic_train, update_train)
    X_test_all, y_test_all = merge_datasets(basic_test, update_test)

    # 5. 라벨 매핑 생성 (업데이트 학습 방식)
    label_to_index, index_to_label = create_label_maps(
        y_basic_train=basic_train[:, -1],
        y_basic_test=basic_test[:, -1],
        y_update_train=update_train[:, -1],
        y_update_test=update_test[:, -1]
    )
    print("label_to_index", label_to_index)
    print("index_to_label", index_to_label)

    # 6. 학습용 입력 데이터 준비
    X_train, y_train = prepare_inputs(X_train_all, y_train_all, label_to_index)
    X_test, y_test = prepare_inputs(X_test_all, y_test_all, label_to_index)

    # 7. 모델 생성 및 학습
    model = build_transfer_model(base_model, len(label_to_index), sorted(label_to_index.values()))
    model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.0001),
                  loss='categorical_crossentropy',
                  metrics=['accuracy'])

    train_model(model, X_train, y_train, X_test, y_test, len(label_to_index))

    combined_train_data = np.column_stack((X_train_all, y_train_all))
    combined_test_data = np.column_stack((X_test_all, y_test_all))

    # 8. 모델 저장 및 TFLite 변환
    save_dir = os.path.join(NEW_DIR, new_model_code)
    os.makedirs(save_dir, exist_ok=True)

    h5_path = os.path.join(save_dir, updated_model_name)
    tflite_path = os.path.join(save_dir, updated_tflite_name)
    combined_train_path = os.path.join(save_dir, combined_train_name)
    combined_test_path = os.path.join(save_dir, combined_test_name)

    convert_to_tflite(model, tflite_path, X_train)
    np.save(combined_train_path, combined_train_data)
    np.save(combined_test_path, combined_test_data)
    model.save(h5_path)


    # 9. 신규 클래스 정보 추출
    existing_labels = set(basic_train[:, -1]) | set(basic_test[:, -1])
    updated_labels = set(update_train[:, -1]) | set(update_test[:, -1])
    all_labels = set(y_train_all) | set(y_test_all)
    new_labels = all_labels - existing_labels

    # 10. Firebase 업로드
    new_tflite_model_url = await upload_model_to_firebase_async(
        combined_train_path,
        combined_test_path,
        h5_path,
        tflite_path,
        new_model_code
    )

    # 11. DB 저장
    # await async_run_in_thread(
    #     save_model_info,
    #     db,
    #     new_model_code,
    #     combined_train_name,
    #     combined_test_name,
    #     updated_model_name
    # )

    return new_model_code, new_tflite_model_url