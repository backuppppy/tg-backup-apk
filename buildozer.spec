[app]
title = TG Backup
package.name = tgbackup
package.domain = org.tgbackup
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,html,txt
source.exclude_dirs = __pycache__,.git,bin,.buildozer

version = 1.0
requirements = python3,kivy==2.3.1,flask==3.0.3,werkzeug,telethon,pyaes,colorama,jinja2,click,itsdangerous,markupsafe

# entrypoint
entrypoint = main.py

# orientation
orientation = portrait

# Android settings
android.api = 33
android.minapi = 21
android.ndk = 25b

# Pin p4a to release that uses Python 3.11 (avoids 3.14 compile issues)
p4a.branch = release-2024.01.21
android.sdk = 33
android.accept_sdk_license = True

android.permissions = INTERNET, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE

# Use a single architecture for faster build; add arm64-v8a for modern devices
android.archs = arm64-v8a, armeabi-v7a

# App icon (optional — add icon.png to project root for custom icon)
# icon.filename = icon.png

# Fullscreen
fullscreen = 0

[buildozer]
log_level = 2
warn_on_root = 0
