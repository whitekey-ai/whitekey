# WhiteKey

Egress policy proxy and credential broker for AI agents. Enforces HTTP/HTTPS allowlists, supports human-in-the-loop approval, and injects encrypted credentials on egress.

## Launch

### Docker Compose

```bash
docker compose up -d
docker exec -it whitekey whitekey <command>
```

Proxy listens on `127.0.0.1:9999` (`PROXY_HOST_BIND`, `PROXY_PORT`). Data persists in `~/.local/share/whitekey` (`WHITEKEY_HOST_DATA_DIR`).

### Local (Java 25+)

```bash
./mvnw clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar whitekey-server/target/whitekey-server-*.jar
java --enable-native-access=ALL-UNNAMED -jar whitekey-cli/target/whitekey-cli-*.jar <command>
```

## Proxy Setup

WhiteKey is a standard HTTP/HTTPS proxy (`127.0.0.1:9999` by default). To inspect HTTPS requests and inject credentials, it terminates TLS using a Root CA (auto-generated on first start if absent):

- **CA file:** `PROXY_CA_CERT_FILE` (default `certs/whitekey_ca.crt` under `WHITEKEY_DATA_DIR`, or an absolute path), stored separately from private keys and tokens.
- **CA endpoint:** `GET /ca.crt` on the Admin API (serves the active CA certificate).
- **Custom CA:** Provide your own certificate and key via `PROXY_CA_CERT_FILE` and `PROXY_CA_KEY_FILE`.

Configure your agent or environment to route HTTP/HTTPS traffic through the proxy and trust this CA certificate for HTTPS.

## CLI

Global flags: `whitekey [--server http://127.0.0.1:1111] [--token <token>] <command>` (also reads `WHITEKEY_SERVER_URL`, `WHITEKEY_ADMIN_TOKEN`, or `.env`). Run `whitekey <command> --help` for full options.

### Setup & Approvals

```bash
whitekey setup on --watch                     # Interactive approval loop; turns OFF on exit
whitekey setup on
whitekey setup off
whitekey setup status

whitekey approvals list
whitekey approvals watch [--interval 300]
whitekey approvals approve <uuid> [--save-rule]
whitekey approvals reject <uuid>
```

### Rules

Precedence: `DENY` > `ASK` > `ALLOW`; unmatched requests fall back to `DEFAULT_POLICY` (`DENY`). `--paths` defaults to `/*`.

```bash
whitekey rules list
whitekey rules add --name allow_github --effect ALLOW --hosts api.github.com
whitekey rules add --name limit_stripe --effect ALLOW --methods POST --hosts api.stripe.com --paths /v1/charges --condition "amount <= 5000 && currency == 'usd'"
whitekey rules add --name deny_admin --effect DENY --hosts api.example.com --paths "/admin/*"
whitekey rules remove allow_github
```

### Credentials

Built-in presets: `github`, `stripe`, `slack`, `openai`, `anthropic`.

```bash
whitekey credentials presets
whitekey credentials list
whitekey credentials add --name github-pat --preset github --secret ghp_...
whitekey credentials add --name my-api --host api.example.com --type HEADER --key X-API-Key --format "%s" --secret sk_...
whitekey credentials remove github-pat
```

### Status & History

```bash
whitekey status
whitekey history [--limit 20] [--status ALLOWED|DENIED|AWAITING_APPROVAL|EXPIRED|CANCELLED|FAILED] [--method GET] [--host api.github.com]
whitekey history clear
```

## Configuration

Set via environment variables or `.env`:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `PORT` / `PROXY_PORT` | `1111` / `9999` | Admin API and agent proxy ports |
| `DEFAULT_POLICY` | `DENY` | Fallback for unmatched requests (`DENY`, `ALLOW`, `ASK`) |
| `WHITEKEY_DATA_DIR` | `~/.local/share/whitekey` | SQLite DB, token, master key, and CA directory |
| `WHITEKEY_ADMIN_TOKEN` | auto-generated | Admin bearer token override |
| `PROXY_TLS_PASSTHROUGH_HOSTS` | — | Comma-separated hosts (`*.domain` supported) tunneled as raw TCP against `CONNECT` rules |

See `whitekey-server/src/main/resources/application.yaml` and `docker-compose.yml` for bind address, timeout, and CA path overrides.
