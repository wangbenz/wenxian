"""开物本地只读裁剪配置；原模型和迁移保留，停用在线维护、V1、搜索及监控。"""

import os
import secrets
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
LOCAL_DATA_DIR = BASE_DIR / ".data"
LOCAL_DATA_DIR.mkdir(exist_ok=True)

# 只读基础版没有登录会话；本地随机密钥不提交到仓库。
SECRET_KEY = os.environ.get("LOREFORGE_SECRET_KEY") or secrets.token_urlsafe(48)
DEBUG = False
ALLOWED_HOSTS = ["127.0.0.1", "localhost", "testserver"]
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# api 包仅保留分页工具；未注册为应用，因此不激活 V1 表和原 quicksetup 命令。
INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "api_v2",
    "rest_framework",
    "django_filters",
    "corsheaders",
    "drf_spectacular",
]
MIDDLEWARE = [
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.security.SecurityMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]
ROOT_URLCONF = "server.urls"
WSGI_APPLICATION = "server.wsgi.application"
DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": LOCAL_DATA_DIR / "loreforge.sqlite3",
        "OPTIONS": {"timeout": 20},
    }
}
LANGUAGE_CODE = "zh-hans"
TIME_ZONE = "UTC"
USE_I18N = True
# 沿用上游的无时区资料日期，保留源 publication_date 的原有含义。
USE_TZ = False
STATIC_URL = "/static/"

REST_FRAMEWORK = {
    "DEFAULT_PAGINATION_CLASS": "api.utils.StandardResultsSetPagination",
    "DEFAULT_FILTER_BACKENDS": [
        "django_filters.rest_framework.DjangoFilterBackend",
        "rest_framework.filters.OrderingFilter",
        "rest_framework.filters.SearchFilter",
    ],
    "DEFAULT_AUTHENTICATION_CLASSES": [],
    "DEFAULT_PERMISSION_CLASSES": [],
    "UNAUTHENTICATED_USER": None,
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
    "DEFAULT_PARSER_CLASSES": ["rest_framework.parsers.JSONParser"],
    "DEFAULT_SCHEMA_CLASS": "drf_spectacular.openapi.AutoSchema",
}
CORS_ALLOW_ALL_ORIGINS = False
CORS_ALLOWED_ORIGINS = [
    "http://127.0.0.1:5178",
    "http://localhost:5178",
]
CORS_ALLOW_METHODS = ["GET", "HEAD", "OPTIONS"]
SPECTACULAR_SETTINGS = {"TITLE": "开物 Loreforge", "VERSION": "local-readonly"}

# 管理命令只接受此目录中的资料，不访问旧 MySQL 或游戏数据。
LOREFORGE_DATA_ROOT = BASE_DIR.parent / "data"
INCLUDE_V1_DATA = False
INCLUDE_V2_DATA = True
BUILD_V1_INDEX = False
BUILD_V2_INDEX = False
