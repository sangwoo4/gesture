import numpy as np
import pandas as pd
import os
import ast

def parse_str_landmarks(str_landmark_list):
    # 문자열 "(x, y, z)" → 튜플 (float, float, float)
    return [ast.literal_eval(coord_str) for coord_str in str_landmark_list]

def preprocess_landmarks_for_2dcnn(landmark_points, handedness_label):
    # 문자열 좌표일 경우 먼저 튜플로 파싱
    if isinstance(landmark_points[0], str):
        landmark_points = parse_str_landmarks(landmark_points)

    landmarks = np.array(landmark_points, dtype=np.float32)

    # 1. 중앙 정렬 (0번 기준)
    base_x, base_y, base_z = landmarks[0]
    landmarks[:, 0] -= base_x
    landmarks[:, 1] -= base_y
    landmarks[:, 2] -= base_z

    # 2. 크기 정규화 (0번 ~ 9번)
    scale_factor = np.linalg.norm(landmarks[0] - landmarks[9])
    if scale_factor > 0:
        landmarks /= scale_factor

    handedness_val = 0 if handedness_label == "Right" else 1
    return np.concatenate([landmarks.flatten(), [handedness_val]])

def convert_landmarks_to_csv(landmarks: list, label: str) -> str:
    landmarks_data = []

    for frame_cords in landmarks:
        feature_vector = preprocess_landmarks_for_2dcnn(frame_cords, "Right")

        # print(feature_vector)
        if feature_vector is None:
            print(f"⚠️ 정규화 실패 (scale=0)")
            continue

        landmarks_data.append(feature_vector)

    df = pd.DataFrame(landmarks_data)
    df.insert(0, "label", [label] * len(landmarks_data))

    csv_path = os.path.join("", "update_hand_landmarks.csv")
    df.to_csv(csv_path, index=False)

    print(f"🎉 CSV 데이터 저장 완료! -> {csv_path}")
    return csv_path
