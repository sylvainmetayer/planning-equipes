#!/usr/bin/env python3
"""Example launcher for the remote solve (ADR 0086, docs/exploitation.md).

The application never creates a machine: it calls this small service, which
holds the provider's token and does. Three calls, all carrying
`Authorization: Bearer $LAUNCHER_SECRET`:

    POST /start   {"instance", "version", "secretWorker"}   idempotent
    GET  /status?instance=...  -> {"state": "creating|ready|absent", "url", "certFingerprint"}
    POST /stop    {"instance"}                              idempotent

It keeps no state of its own: the machine of an instance is found by its
label, and its certificate fingerprint is read back from the labels too, so
restarting the launcher loses nothing.

Two drivers, chosen by LAUNCHER_DRIVER:

  hetzner  a cloud server created from a bare OS image; cloud-init installs
           Docker, closes the firewall and starts the application's image in
           worker mode. Needs HCLOUD_TOKEN, a token of a project dedicated to
           workers — never the one of the project that hosts anything else.
  docker   a container on this very host. For a development machine, and for
           trying the contract without renting anything.

Standard library only, plus the `openssl` binary (and `docker` for that driver).

Environment:
  LAUNCHER_SECRET          required; what the application presents
  LAUNCHER_LISTEN          default 127.0.0.1:8099 — never expose this service:
                           it creates machines
  LAUNCHER_DRIVER          hetzner | docker (default hetzner)
  LAUNCHER_INSTANCES       comma-separated names of the instances served, default
                           planning (the application's SOLVER_WORKER_INSTANCE);
                           any other name is refused
  LAUNCHER_IMAGE           default ghcr.io/sylvainmetayer/planning-equipes;
                           pulled at the version the application asks for
  LAUNCHER_IMAGE_REF       optional: the exact image to run instead of
                           LAUNCHER_IMAGE:<version> — a digest
                           (…/planning-equipes@sha256:…) pins what a tag only
                           names. Yours to keep in step with the application:
                           a worker of another version refuses its problems
  LAUNCHER_PORT            the worker's port, default 8443
  LAUNCHER_CONTAINER_BIN   docker driver only: the binary to call, default docker (podman works)
  LAUNCHER_MAX_HOURS       a machine older than this is deleted whatever the
                           application says (default 6): the net under an
                           application that died with a machine up
  hetzner only:
  HCLOUD_TOKEN             required
  LAUNCHER_SERVER_TYPE     default ccx13 (dedicated cores: the search is single-threaded)
  LAUNCHER_LOCATION        default nbg1
  LAUNCHER_OS_IMAGE        default debian-13, which the cloud-init below is written
                           for (there the Docker client is a package of its own)
  LAUNCHER_ALLOWED_CIDR    who may reach the worker's port, default 0.0.0.0/0;
                           set it to the application's outgoing address
  LAUNCHER_NETWORK         optional id of a private network: the worker is then
                           reached by its private address (it keeps a public one
                           to pull the image, with every incoming port closed)
  LAUNCHER_SSH_KEYS        optional comma-separated key names; without one the
                           provider mails a root password nobody can use (SSH is
                           closed by the firewall either way)
"""

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = os.environ.get("LAUNCHER_SECRET", "")
DRIVER = os.environ.get("LAUNCHER_DRIVER", "hetzner")
IMAGE = os.environ.get("LAUNCHER_IMAGE", "ghcr.io/sylvainmetayer/planning-equipes")
PORT = int(os.environ.get("LAUNCHER_PORT", "8443"))
MAX_HOURS = float(os.environ.get("LAUNCHER_MAX_HOURS", "6"))
LABEL = "solve-worker"
INSTANCES = [name for name in os.environ.get("LAUNCHER_INSTANCES", "planning").split(",") if name]
# What reaches a command line or a cloud-init script is held to a grammar first.
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")  # an image tag
WORKER_SECRET = re.compile(r"[A-Za-z0-9_-]{16,256}")


def image_of(version):
    return os.environ.get("LAUNCHER_IMAGE_REF") or IMAGE + ":" + version


CONTAINER = os.environ.get("LAUNCHER_CONTAINER_BIN", "docker")


def certificate():
    """A key pair for one machine: (PKCS#12 bytes, its password, SHA-256 fingerprint of the certificate)."""
    with tempfile.TemporaryDirectory() as tmp:
        key, crt, p12 = (os.path.join(tmp, name) for name in ("key.pem", "crt.pem", "worker.p12"))
        password = secrets.token_hex(16)
        run("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
            "-keyout", key, "-out", crt, "-days", "30", "-subj", "/CN=solve-worker")
        run("openssl", "pkcs12", "-export", "-inkey", key, "-in", crt, "-out", p12, "-passout", "pass:" + password)
        fingerprint = run("openssl", "x509", "-in", crt, "-noout", "-fingerprint", "-sha256")
        with open(p12, "rb") as file:
            return file.read(), password, fingerprint.split("=", 1)[1].strip().replace(":", "").lower()


def run(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def answers(address, fingerprint):
    """Whether the worker is up: its port presents the certificate made for this machine."""
    try:
        presented = ssl.PEM_cert_to_DER_cert(ssl.get_server_certificate((address, PORT), timeout=5))
    except (OSError, ValueError):
        return False
    return hmac.compare_digest(hashlib.sha256(presented).hexdigest(), fingerprint)


class Hetzner:
    API = "https://api.hetzner.cloud/v1"

    def call(self, method, path, body=None):
        request = urllib.request.Request(
            self.API + path, method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + os.environ["HCLOUD_TOKEN"], "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
            return json.loads(payload) if payload else {}

    def servers(self, instance=None):
        selector = LABEL if instance is None else LABEL + "=" + instance
        return self.call("GET", "/servers?label_selector=" + urllib.parse.quote(selector))["servers"]

    def start(self, instance, version, worker_secret):
        if self.servers(instance):
            return
        p12, password, fingerprint = certificate()
        body = {
            "name": "solve-worker-" + instance,
            "server_type": os.environ.get("LAUNCHER_SERVER_TYPE", "ccx13"),
            "location": os.environ.get("LAUNCHER_LOCATION", "nbg1"),
            "image": os.environ.get("LAUNCHER_OS_IMAGE", "debian-13"),
            # A label value holds 63 characters and a SHA-256 is 64: in two halves.
            "labels": {LABEL: instance, "fp1": fingerprint[:32], "fp2": fingerprint[32:]},
            "user_data": cloud_init(version, worker_secret, p12, password),
            "ssh_keys": [key for key in os.environ.get("LAUNCHER_SSH_KEYS", "").split(",") if key],
        }
        if os.environ.get("LAUNCHER_NETWORK"):
            body["networks"] = [int(os.environ["LAUNCHER_NETWORK"])]
        self.call("POST", "/servers", body)

    def status(self, instance):
        servers = self.servers(instance)
        if not servers:
            return {"state": "absent"}
        server = servers[0]
        private = server.get("private_net") or []
        address = private[0]["ip"] if os.environ.get("LAUNCHER_NETWORK") and private else server["public_net"]["ipv4"]["ip"]
        fingerprint = server["labels"]["fp1"] + server["labels"]["fp2"]
        if server["status"] != "running" or not answers(address, fingerprint):
            return {"state": "creating"}
        return {"state": "ready", "url": "https://%s:%d" % (address, PORT), "certFingerprint": fingerprint}

    def stop(self, instance):
        for server in self.servers(instance):
            self.call("DELETE", "/servers/%d" % server["id"])

    def sweep(self):
        for server in self.servers():
            created = datetime.fromisoformat(server["created"])
            if (datetime.now(timezone.utc) - created).total_seconds() > MAX_HOURS * 3600:
                print("Deleting %s: older than %s h" % (server["name"], MAX_HOURS), flush=True)
                self.call("DELETE", "/servers/%d" % server["id"])


def cloud_init(version, worker_secret, p12, password):
    allowed = os.environ.get("LAUNCHER_ALLOWED_CIDR", "0.0.0.0/0")
    environment = "\n".join([
        "SOLVER_WORKER_MODE=true",
        "SOLVER_WORKER_SECRET=" + worker_secret,
        "SOLVER_WORKER_PORT=%d" % PORT,
        "SOLVER_WORKER_TLS_KEYSTORE=/worker/worker.p12",
        "SOLVER_WORKER_TLS_KEYSTORE_PASSWORD=" + password,
    ])
    # Incoming: the worker's port from the application, nothing else — no SSH.
    # Outgoing: name resolution, time, and HTTP(S) to install Docker and pull
    # the image. Docker's own forwarding chain is left alone: the published
    # port is filtered in `prerouting`, before Docker's NAT sees it.
    firewall = """#!/usr/sbin/nft -f
table inet worker {
  chain prerouting {
    type filter hook prerouting priority -150; policy accept;
    ct state established,related accept
    iifname "lo" accept
    iifname "docker0" accept
    meta l4proto { icmp, ipv6-icmp } accept
    ip saddr %s tcp dport %d accept
    drop
  }
  chain output {
    type filter hook output priority 0; policy drop;
    ct state established,related accept
    oifname "lo" accept
    oifname "docker0" accept
    meta l4proto { icmp, ipv6-icmp } accept
    udp dport { 53, 123 } accept
    tcp dport { 53, 80, 443 } accept
  }
}
""" % (allowed, PORT)
    files = [
        ("/worker/worker.p12", "0444", base64.b64encode(p12).decode()),
        ("/worker/env", "0400", base64.b64encode(environment.encode()).decode()),
        ("/etc/nftables-worker.nft", "0400", base64.b64encode(firewall.encode()).decode()),
    ]
    lines = ["#cloud-config", "package_update: true", "packages: [docker.io, docker-cli, nftables]", "ssh_pwauth: false",
             "write_files:"]
    for path, mode, content in files:
        lines += ["  - path: " + path, "    permissions: '%s'" % mode, "    encoding: b64", "    content: " + content]
    lines += [
        "runcmd:",
        "  - nft -f /etc/nftables-worker.nft",
        "  - docker run -d --name solve-worker --restart unless-stopped -p %d:%d --env-file /worker/env"
        " -v /worker/worker.p12:/worker/worker.p12:ro %s" % (PORT, PORT, image_of(version)),
    ]
    return "\n".join(lines) + "\n"


class Docker:
    """One container on this host; its labels carry its certificate's fingerprint and where its keystore is."""

    def name(self, instance):
        return "solve-worker-" + instance

    def label(self, instance, label):
        """The value of a label of the instance's running container, or None without one."""
        found = run(CONTAINER, "ps", "--filter", "name=^" + self.name(instance) + "$",
                    "--format", '{{.Label "%s"}}' % label).strip()
        return found or None

    def start(self, instance, version, worker_secret):
        if self.label(instance, "fp"):
            return
        p12, password, fingerprint = certificate()
        directory = tempfile.mkdtemp(prefix="solve-worker-")  # ours alone: 0700
        keystore = os.path.join(directory, "worker.p12")
        # shortcut: readable by the container's user thanks to the usual umask
        # (022); under a stricter one, chmod the file after writing it.
        with open(keystore, "wb") as file:
            file.write(p12)
        # The two secrets go through the environment, not the command line, where `ps` would show them.
        secret_environment = dict(os.environ, SOLVER_WORKER_SECRET=worker_secret,
                                  SOLVER_WORKER_TLS_KEYSTORE_PASSWORD=password)
        subprocess.run(
            [CONTAINER, "run", "-d", "--rm", "--name", self.name(instance), "-p", "127.0.0.1:%d:%d" % (PORT, PORT),
             "--label", "fp=" + fingerprint, "--label", "dir=" + directory,
             "-e", "SOLVER_WORKER_MODE=true", "-e", "SOLVER_WORKER_SECRET",
             "-e", "SOLVER_WORKER_PORT=%d" % PORT, "-e", "SOLVER_WORKER_TLS_KEYSTORE=/worker/worker.p12",
             "-e", "SOLVER_WORKER_TLS_KEYSTORE_PASSWORD",
             "-v", keystore + ":/worker/worker.p12:ro,z", image_of(version)],
            check=True, capture_output=True, text=True, env=secret_environment)

    def status(self, instance):
        fingerprint = self.label(instance, "fp")
        if not fingerprint:
            return {"state": "absent"}
        if not answers("127.0.0.1", fingerprint):
            return {"state": "creating"}
        return {"state": "ready", "url": "https://127.0.0.1:%d" % PORT, "certFingerprint": fingerprint}

    def stop(self, instance):
        directory = self.label(instance, "dir")
        if directory:
            run(CONTAINER, "stop", self.name(instance))
            shutil.rmtree(directory, ignore_errors=True)

    def sweep(self):
        pass  # nothing rented


driver = Docker() if DRIVER == "docker" else Hetzner()


class Handler(BaseHTTPRequestHandler):

    def answer(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def authorised(self):
        return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + SECRET)

    def read_call(self, method, route, url):
        """The instance the caller names and, for a POST, the whole body."""
        if method == "GET" and route == "status":
            return urllib.parse.parse_qs(url.query)["instance"][0], None
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        return body["instance"], body

    def run_call(self, method, route, instance, body):
        if method == "GET" and route == "status":
            return self.answer(200, driver.status(instance))
        if method == "POST" and route == "start":
            if not VERSION.fullmatch(body["version"]) or not WORKER_SECRET.fullmatch(body["secretWorker"]):
                return self.answer(400, {"error": "version or secretWorker: unexpected characters"})
            driver.start(instance, body["version"], body["secretWorker"])
            return self.answer(200, {"state": "creating"})
        if method == "POST" and route == "stop":
            driver.stop(instance)
            return self.answer(200, {"state": "absent"})
        return self.answer(404, {"error": "no such route"})

    def handle_call(self, method):
        if not self.authorised():
            return self.answer(401, {"error": "missing or wrong secret"})
        url = urllib.parse.urlparse(self.path)
        route = url.path.rstrip("/").rsplit("/", 1)[-1]
        try:
            named, body = self.read_call(method, route, url)
            # The name used from here on is the operator's own, never the caller's string.
            instance = next((name for name in INSTANCES if name == named), None)
            if instance is None:
                return self.answer(400, {"error": "instance: not one of LAUNCHER_INSTANCES"})
            return self.run_call(method, route, instance, body)
        except (KeyError, ValueError) as error:
            return self.answer(400, {"error": "bad request: %s" % error})
        except Exception as error:  # the provider said no, or is down: the application reads the status
            print("%s %s failed: %s" % (method, self.path, error), flush=True)
            return self.answer(502, {"error": str(error)})

    def do_GET(self):
        self.handle_call("GET")

    def do_POST(self):
        self.handle_call("POST")

    def log_message(self, format, *args):
        print(self.address_string(), format % args, flush=True)


def sweep_forever():
    while True:
        try:
            driver.sweep()
        except Exception as error:
            print("Sweep failed: %s" % error, flush=True)
        time.sleep(600)


if __name__ == "__main__":
    if not SECRET:
        raise SystemExit("LAUNCHER_SECRET is required")
    host, _, port = os.environ.get("LAUNCHER_LISTEN", "127.0.0.1:8099").rpartition(":")
    threading.Thread(target=sweep_forever, daemon=True).start()
    print("Launcher (%s driver) listening on %s:%s" % (DRIVER, host, port), flush=True)
    ThreadingHTTPServer((host, int(port)), Handler).serve_forever()
