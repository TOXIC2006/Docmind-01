import email.policy
import json
import socketserver
import threading
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

messages = []
lock = threading.Lock()


class SMTP(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(30)
        self.wfile.write(b"220 localhost ESMTP integration-test inbox\r\n")
        recipient = ""
        while line := self.rfile.readline(65536):
            command = line.decode(errors="replace").strip()
            upper = command.upper()
            if upper.startswith(("EHLO", "HELO")):
                self.wfile.write(b"250 localhost\r\n")
            elif upper.startswith("MAIL FROM:"):
                self.wfile.write(b"250 OK\r\n")
            elif upper.startswith("RCPT TO:"):
                recipient = command.partition(":")[2].strip().strip("<>")
                if not recipient.endswith("@example.test"):
                    self.wfile.write(b"550 Only example.test recipients are accepted\r\n")
                    recipient = ""
                else:
                    self.wfile.write(b"250 OK\r\n")
            elif upper == "DATA" and recipient:
                self.wfile.write(b"354 End with a dot\r\n")
                body = bytearray()
                while data := self.rfile.readline(65536):
                    if data == b".\r\n":
                        break
                    body.extend(data[1:] if data.startswith(b"..") else data)
                    if len(body) > 1000000:
                        return
                parsed = BytesParser(policy=email.policy.default).parsebytes(bytes(body))
                html = next((part.get_content() for part in parsed.walk()
                             if part.get_content_type() == "text/html"), "")
                with lock:
                    messages.append({"to": recipient, "subject": str(parsed["Subject"]), "html": html})
                self.wfile.write(b"250 Accepted locally; no external delivery\r\n")
            elif upper == "QUIT":
                self.wfile.write(b"221 Bye\r\n")
                return
            elif upper in ("RSET", "NOOP"):
                self.wfile.write(b"250 OK\r\n")
            else:
                self.wfile.write(b"502 Unsupported command\r\n")


class Inspector(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/messages":
            self.send_error(404)
            return
        with lock:
            body = json.dumps(messages).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    with socketserver.ThreadingTCPServer(("127.0.0.1", 1025), SMTP) as smtp:
        smtp.daemon_threads = True
        threading.Thread(target=smtp.serve_forever, daemon=True).start()
        print("Test SMTP: 127.0.0.1:1025; inspector: 127.0.0.1:8025/messages", flush=True)
        with ThreadingHTTPServer(("127.0.0.1", 8025), Inspector) as http:
            http.serve_forever()
