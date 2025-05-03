import tensorflow as tf
import numpy as np
import os
import json
import time
from collections import Counter
from config import BASE_DIR, NEW_DIR

from utils.model_io import get_model_info, download_model, save_model_info
from utils.preprocessing import generate_model_filename, new_split_landmarks, find_duplicate_label_pairs_by_distance, \
    generate_file_names
from utils.model_builder import new_convert_to_npy
from services.firebase_service import upload_model_to_firebase

from sqlalchemy.orm import Session
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Dense, Flatten
from tensorflow.keras.utils import to_categorical
from tensorflow.keras.callbacks import EarlyStopping
from sklearn.utils import class_weight


def prepare_datasets(model_info):
    train_data = np.load(os.path.join(BASE_DIR, model_info.Train_Data), allow_pickle=True)
    test_data = np.load(os.path.join(BASE_DIR, model_info.Test_Data), allow_pickle=True)
    model_path = os.path.join(BASE_DIR, model_info.Model)
    model = load_model(model_path)
    return train_data, test_data, model


def merge_datasets(basic_data, update_data):
    X1, y1 = basic_data[:, :-1].astype(np.float32), basic_data[:, -1].astype(str)
    X2, y2 = update_data[:, :-1].astype(np.float32), update_data[:, -1].astype(str)
    return np.concatenate([X1, X2]), np.concatenate([y1, y2])


def create_label_maps(y_train, y_test):
    label_to_index = {}
    index_to_label = {}

    # 기존 라벨을 처리
    for label in list(y_train) + list(y_test):
        if label not in label_to_index:
            idx = len(label_to_index)
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
    new_model.add(Dense(64, activation='relu', kernel_initializer='he_normal', name=dense_name))
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
        print(f"중복률이 {threshold:.1f}% 이상입니다. 학습 중단.")
        exit(1)


def save_model_and_convert_tflite(model, save_path_h5, save_path_tflite):
    model.save(save_path_h5)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    with open(save_path_tflite, 'wb') as f:
        f.write(tflite_model)


def train_model(model, X_train, y_train, X_test, y_test):
    early_stop = EarlyStopping(monitor='val_loss', patience=5, restore_best_weights=True)
    y_train_idx = np.argmax(y_train, axis=1)
    weights = class_weight.compute_class_weight('balanced', classes=np.unique(y_train_idx), y=y_train_idx)
    class_weight_dict = dict(enumerate(weights))

    model.fit(
        X_train, y_train,
        epochs=1000,
        batch_size=32,
        validation_data=(X_test, y_test),
        callbacks=[early_stop],
        class_weight=class_weight_dict
    )

def load_npy_data(file_path: str) -> np.ndarray:
    return np.load(os.path.join(NEW_DIR, file_path), allow_pickle=True)

def get_new_labels(base_train: np.ndarray, base_test: np.ndarray, y_train_all: list, y_test_all: list) -> set:
    existing_labels = set(base_train[:, -1]) | set(base_test[:, -1])
    all_labels = set(y_train_all) | set(y_test_all)
    new_labels = all_labels - existing_labels

    print(f"✅ 기존 라벨 수: {len(existing_labels)}")
    print(f"✅ 전체 라벨 수: {len(all_labels)}")
    print(f"🆕 새로 추가된 라벨: {new_labels}")
    return new_labels

def train_new_model_service(model_code: str, landmarks: list, db: Session, gesture: str) -> tuple[str, str, str]:
    new_model_code = generate_model_filename()
    updated_train_name = f"update_{new_model_code}_train_hand_landmarks.npy"
    updated_test_name = f"update_{new_model_code}_test_hand_landmarks.npy"
    updated_model_name = f"update_{new_model_code}_model_cnn.h5"
    updated_tflite_name = f"update_{new_model_code}_cnn.tflite"

    model_info = get_model_info(model_code, db)
    download_model(model_info)
    basic_train, basic_test, base_model = prepare_datasets(model_info)

    update_train_path, update_test_path = new_split_landmarks(new_convert_to_npy(), updated_train_name, updated_test_name)
    update_train = np.load(os.path.join(NEW_DIR, update_train_path), allow_pickle=True)
    update_test = np.load(os.path.join(NEW_DIR, update_test_path), allow_pickle=True)

    check_duplicates(
        base_data={'train': basic_train, 'test': basic_test},
        update_data={'train': update_train, 'test': update_test}
    )

    X_train_all, y_train_all = merge_datasets(basic_train, update_train)
    X_test_all, y_test_all = merge_datasets(basic_test, update_test)

    label_to_index, index_to_label = create_label_maps(y_train_all, y_test_all)
    label_ids = sorted(label_to_index.values())

    X_train, y_train = prepare_inputs(X_train_all, y_train_all, label_to_index)
    X_test, y_test = prepare_inputs(X_test_all, y_test_all, label_to_index)

    model = build_transfer_model(base_model, len(label_to_index), label_ids)
    model.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=0.0005),
                  loss='categorical_crossentropy',
                  metrics=['accuracy'])

    early_stop = EarlyStopping(monitor='val_loss', patience=10, restore_best_weights=True)

    y_train_idx = np.argmax(y_train, axis=1)
    weights = class_weight.compute_class_weight('balanced', classes=np.unique(y_train_idx), y=y_train_idx)
    class_weight_dict = dict(enumerate(weights))

    model.fit(X_train, y_train,
              epochs=1000,
              batch_size=16,
              validation_data=(X_test, y_test),
              callbacks=[early_stop],
              class_weight=class_weight_dict)

    h5_path = os.path.join(NEW_DIR, updated_model_name)
    tflite_path = os.path.join(NEW_DIR, updated_tflite_name)

    save_model_and_convert_tflite(model, h5_path, tflite_path)

    existing_labels = set(basic_train[:, -1]) | set(basic_test[:, -1])
    updated_labels = set(update_train[:, -1]) | set(update_test[:, -1])

    all_labels = set(y_train_all) | set(y_test_all)
    new_labels = all_labels - existing_labels

    print(f"✅ 기존 라벨 수: {len(existing_labels)}")
    print(f"✅ 전체 라벨 수: {len(all_labels)}")
    print(f"🆕 새로 추가된 라벨: {all_labels}")

    save_model_info(
        db=db,
        new_model_code = new_model_code,
        updated_train_name=updated_train_name,
        updated_test_name=updated_test_name,
        updated_model_name=updated_model_name
    )

    new_tflite_model_url = upload_model_to_firebase(update_train_path, update_test_path, h5_path, tflite_path)

    return new_model_code, new_tflite_model_url, new_labels