# ─────────────────────────────────────────────────────────────
# Screens CMS backend container.
#
# What's running: serve.py — a stdlib HTTP server that serves the React/
# Babel CMS at /, exposes /api/* for the player APK, and (in cloud mode)
# pipes media through the Drive API.
#
# Two modes, picked at runtime by env:
#
#   • LOCAL  — no Drive vars set. Reads media from SCREENS_MEDIA_DIR /
#              SCREENS_SPLASH_DIR on the local filesystem (the
#              Drive-for-Desktop mount on a dev laptop).
#
#   • CLOUD  — GOOGLE_APPLICATION_CREDENTIALS + at least one of
#              SCREENS_DRIVE_BRANDS_ID / SCREENS_DRIVE_SPLASHES_ID set.
#              The container hits Drive directly via service account,
#              streams brand videos through /media/<drive_id>, and
#              caches splashes into /tmp at boot so /splash/<name>
#              serves from local disk.
#
# What you must set on Cloud Run for cloud mode:
#   • Mount Secret `drive-credentials` at /secrets/drive-credentials.json
#   • GOOGLE_APPLICATION_CREDENTIALS=/secrets/drive-credentials.json
#   • SCREENS_DRIVE_BRANDS_ID=<Brand Content folder ID>
#   • SCREENS_DRIVE_SPLASHES_ID=<splash root folder ID>
#   • Deploy with --min-instances=1 --max-instances=1 (state is in-memory)
#
# In-memory state caveat (still): tablet registry, command queue, and
# per-screen playlist live in Python dicts. Restart wipes them. Pin to
# one instance until that's swapped for Firestore / Cloud SQL.
# ─────────────────────────────────────────────────────────────

FROM python:3.11-slim

# curl is convenient for Cloud Run startup probes and ad-hoc debugging.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Drive API client up front — these are the only third-party
# Python deps. requirements.txt is intentionally tiny.
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Copy what serve.py + scan-videos.py actually need at runtime. Player
# source, build output, and IDE noise are excluded via .dockerignore.
COPY serve.py        /app/serve.py
COPY scan-videos.py  /app/scan-videos.py
COPY drive_client.py /app/drive_client.py
COPY app/            /app/app/
COPY brand/          /app/brand/

# Cloud Run sets PORT to 8080 by default. EXPOSE is documentation only —
# Cloud Run ignores it, but it's useful when running the container
# locally (`docker run -p 8080:8080 ...`).
ENV PORT=8080
EXPOSE 8080

# Default media dirs to placeholder paths inside the container. These
# only matter in LOCAL mode (filesystem reads). In CLOUD mode the
# Drive-API code paths bypass them entirely. serve.py prints a warning
# if MEDIA_DIR doesn't exist but boots regardless.
ENV SCREENS_MEDIA_DIR=/app/media
ENV SCREENS_SPLASH_DIR=/app/splash

# `-u` flushes stdout immediately so Cloud Run's log stream picks up
# every line without buffering.
CMD ["python", "-u", "serve.py"]
