# ─────────────────────────────────────────────────────────────
# Screens CMS backend container.
#
# What's running: serve.py — a stdlib-only Python HTTP server that
# both serves the React/Babel CMS at / and exposes the /api/* endpoints
# the player APK talks to. No third-party Python deps; no requirements.txt
# needed.
#
# Cloud Run injects $PORT (defaults to 8080). serve.py reads it via
# os.environ["PORT"] — see the PORT line in serve.py.
#
# Things to be aware of when deploying to Cloud Run / similar:
#
#   • State is in-memory only. Every container restart wipes the registered
#     tablets, queued commands, and per-screen playlists. Run with
#     --min-instances=1 --max-instances=1 to avoid the worst of this; for
#     real persistence, swap _per_screen / _screens for a Cloud SQL or
#     Firestore-backed store.
#
#   • Media isn't bundled. The Drive-mounted Brand Content/ folder doesn't
#     exist inside the container, so the content library will be empty
#     until SCREENS_MEDIA_DIR / SCREENS_SPLASH_DIR point at a Cloud Storage
#     mount or similar. The CMS UI loads fine without it; pushes from CMS
#     to tablets just won't have anything to push.
# ─────────────────────────────────────────────────────────────

FROM python:3.11-slim

# Curl is handy for Cloud Run's startup probe and for debugging from the
# container; tiny in the slim image. Skip if you're size-sensitive.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the parts the server actually needs at runtime. Player source,
# build output, and IDE noise are excluded via .dockerignore.
COPY serve.py        /app/serve.py
COPY scan-videos.py  /app/scan-videos.py
COPY app/            /app/app/
COPY brand/          /app/brand/

# Cloud Run sets PORT to 8080 by default. Expose for documentation —
# Cloud Run ignores EXPOSE but it helps when running locally.
ENV PORT=8080
EXPOSE 8080

# The defaults inside MEDIA_DIR / SPLASH_DIR point at G:\… (Windows dev
# paths). In the container we overwrite them with paths that don't exist
# yet — serve.py prints a warning and keeps booting. Mount real paths in
# at deploy time when you have somewhere to put the media.
ENV SCREENS_MEDIA_DIR=/app/media
ENV SCREENS_SPLASH_DIR=/app/splash

CMD ["python", "-u", "serve.py"]
