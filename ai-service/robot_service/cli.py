"""本地管理员入口；不会自动修改防火墙、路由器或系统自启动。"""

import argparse
import ipaddress
import json
import secrets
import socket
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from .store import Store, digest


def initialize(root: Path, host: str):
    store = Store(root)
    cert_path = root / "server.pem"
    key_path = root / "server.key"
    if cert_path.exists() != key_path.exists():
        raise RuntimeError("证书或私钥缺失；请恢复成对身份文件，不自动替换")
    if not cert_path.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        name = x509.Name(
            [x509.NameAttribute(NameOID.COMMON_NAME, store.meta("service_id"))]
        )
        sans = [
            x509.DNSName("localhost"),
            x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
        ]
        try:
            sans.append(x509.IPAddress(ipaddress.ip_address(host)))
        except ValueError:
            sans.append(x509.DNSName(host))
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(datetime.now(timezone.utc) - timedelta(minutes=5))
            .not_valid_after(datetime.now(timezone.utc) + timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName(sans), critical=False)
            .sign(key, hashes.SHA256())
        )
        key_path.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        key_path.chmod(0o600)
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    recovery = root / "recovery.secret"
    if not recovery.exists():
        recovery.write_text(secrets.token_urlsafe(48))
        recovery.chmod(0o600)
        with store.transaction() as db:
            db.execute(
                "INSERT OR REPLACE INTO meta VALUES('recovery_hash',?)",
                (digest(recovery.read_text()),),
            )
    return {
        "serviceId": store.meta("service_id"),
        "certificateSha256": cert.fingerprint(hashes.SHA256()).hex(),
    }


def main():
    p = argparse.ArgumentParser(description="家庭机器人服务")
    p.add_argument("--data", type=Path, default=Path("runtime"))
    sub = p.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init")
    init.add_argument("--host", default=socket.gethostname())
    init.add_argument("--port", type=int, default=8766)
    reg = sub.add_parser("invite")
    reg.add_argument("--host", default=socket.gethostname())
    reg.add_argument("--port", type=int, default=8766)
    serve = sub.add_parser("serve")
    serve.add_argument("--bind", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8766)
    recovery = sub.add_parser("recover")
    recovery.add_argument("--robot", required=True)
    recovery.add_argument("--host", default=socket.gethostname())
    recovery.add_argument("--port", type=int, default=8766)
    backup = sub.add_parser("backup")
    backup.add_argument("output", type=Path)
    restore = sub.add_parser("restore")
    restore.add_argument("input", type=Path)
    ingest = sub.add_parser(
        "import-directory", help="将 inbox 的一个子目录导入为一本待审核图书"
    )
    ingest.add_argument("folder", help="相对于数据目录 inbox 的目录名")
    ingest.add_argument("--title", default=None)
    args = p.parse_args()
    root = args.data.resolve()
    if args.command in ("init", "invite", "recover"):
        info = initialize(root, args.host)
        if args.command == "recover" and not Store(root).one(
            "SELECT 1 FROM devices WHERE id=? AND role='robot'", (args.robot,)
        ):
            p.error("机器人ID不存在")
        info.update(
            {
                "protocolVersion": 1,
                "address": f"https://{args.host}:{args.port}",
                "invite": Store(root).issue_invite("recover", args.robot)
                if args.command == "recover"
                else Store(root).issue_invite("register"),
                "expiresIn": 120,
            }
        )
        info["purpose"] = "recover" if args.command == "recover" else "register"
        # 连接材料写受限文件，控制台只显示位置，避免常规日志泄露。
        target = root / "connection.json"
        target.write_text(json.dumps(info, ensure_ascii=False, indent=2))
        target.chmod(0o600)
        print(f"连接材料：{target}（两分钟有效，亲自在手机导入）；恢复凭据请单独保管。")
    elif args.command == "import-directory":
        from .inbox import import_directory

        try:
            print(
                json.dumps(
                    import_directory(Store(root), args.folder, args.title),
                    ensure_ascii=False,
                    indent=2,
                )
            )
        except ValueError as exc:
            p.error(str(exc))
    elif args.command == "backup":
        from .management import export_library

        args.output.write_bytes(export_library(Store(root)))
        args.output.chmod(0o600)
        print("资源备份完成（不含设备凭据）")
    elif args.command == "restore":
        from .management import restore_library

        if args.input.stat().st_size > 512 * 1024 * 1024:
            p.error("备份超过512MB")
        print(
            json.dumps(
                restore_library(Store(root), args.input.read_bytes()),
                ensure_ascii=False,
            )
        )
    else:
        if not (root / "server.key").exists():
            p.error("先运行 init")
        import uvicorn

        from .app import create_app

        uvicorn.run(
            create_app(root),
            host=args.bind,
            port=args.port,
            ssl_keyfile=str(root / "server.key"),
            ssl_certfile=str(root / "server.pem"),
            access_log=False,
        )


if __name__ == "__main__":
    main()
