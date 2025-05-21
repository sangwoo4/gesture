# 1. 베이스 이미지
FROM python:3.10

# 2. 작업 디렉토리 설정
WORKDIR /app

# 3. 시스템 의존성 설치
RUN apt-get update && \
    apt-get install -y --no-install-recommends gcc libffi-dev libssl-dev build-essential && \
    rm -rf /var/lib/apt/lists/*

# 4. 파일 복사 (env 포함!)
COPY requirements.txt ./
COPY .env ./
COPY model-upload-server-firebase-adminsdk-fbsvc-60bfb2f2fd.json ./

# 5. pip 설치
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt --no-deps

# 6. tensorflow는 따로 설치
RUN pip install --no-cache-dir tensorflow==2.13.0

# 7. 앱 소스 복사
COPY app/ ./app/

# 8. 포트 설정
EXPOSE 8000

# 9. 앱 실행 (uvicorn)
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]