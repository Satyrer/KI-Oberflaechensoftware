FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    XDG_CACHE_HOME=/models

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir faster-whisper==1.1.1 requests

WORKDIR /app
COPY media_transcription_worker.py /app/media_transcription_worker.py

EXPOSE 8091
CMD ["python", "/app/media_transcription_worker.py"]
