"""沿用 Django 开发服务器，仅将本地默认地址设为开物专用端口。"""

from django.core.management.commands.runserver import Command as DjangoRunserverCommand


class Command(DjangoRunserverCommand):
    default_addr = "127.0.0.1"
    default_port = "18087"
