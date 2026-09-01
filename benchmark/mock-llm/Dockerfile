FROM python:3.13-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8011

# 压测节奏可通过环境变量覆盖：TTFT_MS / CHUNK_INTERVAL_MS / CHUNK_COUNT
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8011"]
