import cv2
import numpy as np
import mediapipe as mp
import tensorflow.lite as tflite

# Mediapipe 설정
mp_hands = mp.solutions.hands
mp_drawing = mp.solutions.drawing_utils
hands = mp_hands.Hands(
    static_image_mode=False,
    max_num_hands=1,
    min_detection_confidence=0.5
)

# TFLite 모델 로드
interpreter = tflite.Interpreter(model_path="new_models/update_gesture_model_cnn.tflite")
interpreter.allocate_tensors()
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

# 웹캠 설정
cap = cv2.VideoCapture(0)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)

# 전처리 함수
def preprocess_landmarks(landmarks, handedness_label):
    landmarks = np.array(landmarks, dtype=np.float32)
    base_x, base_y, base_z = landmarks[0]
    landmarks[:, 0] -= base_x
    landmarks[:, 1] -= base_y
    landmarks[:, 2] -= base_z
    scale_factor = np.linalg.norm(landmarks[0] - landmarks[9])
    if scale_factor > 0:
        landmarks /= scale_factor
    handedness_val = 0 if handedness_label == "Right" else 1
    return np.concatenate([landmarks.flatten(), [handedness_val]])

confidence_threshold = 0.7

# 실시간 추론 루프
while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        print("🚨 카메라 프레임을 가져올 수 없습니다.")
        break

    image_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    results = hands.process(image_rgb)

    if results.multi_hand_landmarks and results.multi_handedness:
        for hand_landmarks, handedness in zip(results.multi_hand_landmarks, results.multi_handedness):
            landmark_points = [[lm.x, lm.y, lm.z] for lm in hand_landmarks.landmark]
            handedness_label = handedness.classification[0].label

            # 전처리 및 float 입력 추출
            landmark_points = preprocess_landmarks(landmark_points, handedness_label)[:63].astype(np.float32)

            # TFLite 모델의 입력 quantization 정보 적용
            input_scale, input_zero_point = input_details[0]['quantization']
            quantized_input = (landmark_points / input_scale + input_zero_point).astype(np.int8)
            quantized_input = quantized_input.reshape(1, 21, 3, 1)

            # 추론
            interpreter.set_tensor(input_details[0]['index'], quantized_input)
            interpreter.invoke()

            output_data = interpreter.get_tensor(output_details[0]['index'])

            predicted_class = int(np.argmax(output_data))
            output_scale, output_zero_point = output_details[0]['quantization']
            confidence = (output_data[0][predicted_class] - output_zero_point) * output_scale

            # 디버그 출력
            print(f"[DEBUG] predicted_class={predicted_class}, confidence={confidence:.2f}")

            if confidence > confidence_threshold:
                label_text = f"Class {predicted_class} ({confidence:.2f})"
            else:
                label_text = "확신 낮음"

            # 시각화
            cv2.putText(frame, label_text, (30, 50), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
            mp_drawing.draw_landmarks(frame, hand_landmarks, mp_hands.HAND_CONNECTIONS)

    cv2.imshow("TFLite 제스처 인식", frame)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
