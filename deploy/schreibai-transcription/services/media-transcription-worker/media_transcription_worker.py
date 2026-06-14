#!/usr/bin/env python3
from __future__ import annotations

import base64
import json
import os
import tempfile
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib import request
from urllib.parse import urlparse

from faster_whisper import WhisperModel


HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8091"))
ASR_MODEL = os.environ.get("ASR_MODEL", "small")
ASR_DEVICE = os.environ.get("ASR_DEVICE", "cpu")
ASR_COMPUTE_TYPE = os.environ.get("ASR_COMPUTE_TYPE", "int8")
ASR_DEFAULT_LANGUAGE = os.environ.get("ASR_DEFAULT_LANGUAGE", "de")
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://ollama-gateway:11434").rstrip("/")
OLLAMA_CHAT_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "mistral-rag:latest")
OLLAMA_TIMEOUT = int(os.environ.get("OLLAMA_TIMEOUT", "900"))

MODEL: WhisperModel | None = None
LANGUAGE_CODES = {
    "auto", "de", "en", "fr", "es", "it", "pt", "nl", "pl", "tr",
    "ru", "uk", "ja", "ko", "zh",
}
OUTPUT_MODES = {"plain", "structured"}


def model() -> WhisperModel:
    global MODEL
    if MODEL is None:
        MODEL = WhisperModel(ASR_MODEL, device=ASR_DEVICE, compute_type=ASR_COMPUTE_TYPE, download_root="/models")
    return MODEL


def read_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    length = int(handler.headers.get("content-length", "0") or "0")
    raw = handler.rfile.read(length).decode("utf-8-sig") if length else "{}"
    return json.loads(raw) if raw.strip() else {}


def write_json(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("content-type", "application/json; charset=utf-8")
    handler.send_header("content-length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def normalize_language(value: Any) -> str:
    language = str(value or ASR_DEFAULT_LANGUAGE).strip().lower()
    aliases = {
        "deutsch": "de",
        "german": "de",
        "englisch": "en",
        "english": "en",
        "automatic": "auto",
        "automatisch": "auto",
    }
    language = aliases.get(language, language)
    if language not in LANGUAGE_CODES:
        allowed = ", ".join(sorted(LANGUAGE_CODES))
        raise ValueError(f"Unsupported language '{language}'. Allowed: {allowed}")
    return language


def normalize_output_mode(payload: dict[str, Any]) -> str:
    raw = str(
        payload.get("outputMode")
        or payload.get("output_mode")
        or payload.get("responseFormat")
        or payload.get("response_format")
        or payload.get("format")
        or "structured"
    ).strip().lower()
    aliases = {
        "text": "plain",
        "txt": "plain",
        "raw": "plain",
        "plain_text": "plain",
        "json": "structured",
        "metadata": "structured",
    }
    mode = aliases.get(raw, raw)
    if mode not in OUTPUT_MODES:
        raise ValueError("Unsupported output mode. Use 'plain' or 'structured'.")
    return mode


def read_optional_fields(payload: dict[str, Any], prefix: str, count: int = 8) -> list[dict[str, str]]:
    values: list[dict[str, str]] = []
    for index in range(1, count + 1):
        raw = payload.get(f"{prefix}{index}") or payload.get(f"{prefix}_{index}")
        value = str(raw or "").strip()
        if value:
            values.append({"id": f"{prefix}{index}", "name": value})
    return values


def build_structured_text(transcript: str, structure: dict[str, Any]) -> str:
    lines = ["# Transkript", "", transcript.strip()]
    speakers = structure.get("speakers") or []
    narrators = structure.get("narrationVoices") or []
    notes = str(structure.get("notes") or "").strip()
    if speakers:
        lines.extend(["", "## Sprecher"])
        lines.extend(f"- {item['id']}: {item['name']}" for item in speakers)
    if narrators:
        lines.extend(["", "## Erzaehlstimmen"])
        lines.extend(f"- {item['id']}: {item['name']}" for item in narrators)
    if notes:
        lines.extend(["", "## Angaben", notes])
    return "\n".join(lines).strip()


def repair_text_encoding(text: str) -> str:
    value = str(text or "")
    markers = ("Ã", "Â", "â€", "â€“", "â€œ", "â€ž")
    if not any(marker in value for marker in markers):
        return value
    try:
        repaired = value.encode("cp1252").decode("utf-8")
    except UnicodeError:
        return value
    bad_before = sum(value.count(marker) for marker in markers)
    bad_after = sum(repaired.count(marker) for marker in markers)
    return repaired if bad_after < bad_before else value


def call_ollama(prompt: str, temperature: float = 0.1, num_predict: int = 1400) -> str:
    payload = {
        "model": OLLAMA_CHAT_MODEL,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": temperature, "num_predict": num_predict},
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        f"{OLLAMA_BASE_URL}/api/generate",
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with request.urlopen(req, timeout=OLLAMA_TIMEOUT) as response:
        parsed = json.loads(response.read().decode("utf-8"))
    return repair_text_encoding(str(parsed.get("response", ""))).strip()


def ai_structure_transcript(transcript: str, structure: dict[str, Any], language: str) -> dict[str, Any]:
    speakers = ", ".join(item["name"] for item in structure.get("speakers") or []) or "keine"
    narrators = ", ".join(item["name"] for item in structure.get("narrationVoices") or []) or "keine"
    excerpt = transcript[:16000]
    prompt = f"""Du bist die lokale SchreibAI-Transkriptionsnachbearbeitung.
Arbeite ausschliesslich lokal mit dem gegebenen Transkript.
Sprache: {language}
Sprecherfelder: {speakers}
Erzaehlstimmen: {narrators}

Aufgabe:
- Repariere keine Fakten und erfinde keine Sprecherwechsel.
- Gib eine kompakte JSON-Antwort ohne Markdown-Codeblock.
- Nutze die vorhandenen Sprecher-/Erzaehlstimmen-Felder als Labels.
- Erzeuge kurze Hinweise zu erkannten Rollen, Qualitaet und moeglichen Korrekturstellen.

JSON-Schema:
{{
  "labels": {{"speakers": [], "narrationVoices": []}},
  "qualityNotes": [],
  "suggestedCorrections": [],
  "shortSummary": ""
}}

Transkript-Auszug:
{excerpt}
"""
    try:
        raw = call_ollama(prompt)
        return {"ok": True, "raw": raw}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def transcribe_audio(audio_path: Path, language: str) -> str:
    whisper_language = None if language == "auto" else language
    segments, _info = model().transcribe(
        str(audio_path),
        language=whisper_language,
        vad_filter=True,
        beam_size=5,
    )
    lines: list[str] = []
    for segment in segments:
        text = segment.text.strip()
        if text:
            lines.append(text)
    return repair_text_encoding("\n".join(lines).strip())


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}")

    def do_GET(self) -> None:
        if urlparse(self.path).path == "/health":
            write_json(self, 200, {
                "ok": True,
                "service": "media-transcription-worker",
                "model": ASR_MODEL,
                "device": ASR_DEVICE,
            })
            return
        write_json(self, 404, {"error": "not_found"})

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/transcribe":
            write_json(self, 404, {"error": "not_found"})
            return
        try:
            payload = read_json(self)
            language = normalize_language(payload.get("language"))
            output_mode = normalize_output_mode(payload)
            filename = str(payload.get("filename") or "audio.bin")
            encoded = str(payload.get("data") or "")
            if not encoded:
                raise ValueError("Missing base64 audio data")
            suffix = Path(filename).suffix or ".audio"
            with tempfile.NamedTemporaryFile(prefix="schreibai-transcription-", suffix=suffix, delete=False) as handle:
                temp_path = Path(handle.name)
                handle.write(base64.b64decode(encoded))
            try:
                transcript = transcribe_audio(temp_path, language)
            finally:
                temp_path.unlink(missing_ok=True)
            structure = {
                "speakers": read_optional_fields(payload, "speakerName"),
                "narrationVoices": read_optional_fields(payload, "narratorVoice"),
                "notes": str(payload.get("notes") or payload.get("metadata") or "").strip(),
            }
            ai_structure = ai_structure_transcript(transcript, structure, language) if output_mode == "structured" else None
            structured_text = build_structured_text(transcript, structure)
            write_json(self, 200, {
                "ok": True,
                "mode": output_mode,
                "language": language,
                "filename": filename,
                "transcript": transcript,
                "plainText": transcript,
                "text": transcript if output_mode == "plain" else structured_text,
                "structure": structure,
                "ai": ai_structure,
            })
        except Exception as exc:
            write_json(self, 500, {
                "ok": False,
                "error": str(exc),
                "trace": traceback.format_exc(limit=8),
            })


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"media-transcription-worker listening on {HOST}:{PORT}, model={ASR_MODEL}, device={ASR_DEVICE}")
    server.serve_forever()


if __name__ == "__main__":
    main()
