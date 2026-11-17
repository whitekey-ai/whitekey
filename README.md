# WhiteKey

Egress policy engine, control plane, and credential broker for AI agents. WhiteKey enforces egress rules with a configurable default policy (`DENY`, `ALLOW`, or `REQUIRE_APPROVAL`), supports interactive human approval, and injects encrypted credentials on outbound requests so agents never see raw secrets.

---

## Launch

### Option 1: Docker Compose (Recommended for Sandbox Isolation)

```bash
docker compose up -d
```

*(Pass `--build` to build locally from source instead of pulling `whitekey-ai/whitekey:latest`.)*

- Proxy listens on `127.0.0.1:9999` on the host and `whitekey:9999` inside the isolated `whitekey-sandbox` network.
- Admin API binds to `127.0.0.1:1111` inside the container; run CLI commands via:

```bash
docker exec -it whitekey whitekey <command>
```

- Persistent data (`whitekey.sqlite`, `whitekey_admin_token`, `whitekey_master.key`) is stored in `~/.local/share/whitekey` (override with `WHITEKEY_HOST_DATA_DIR`).

### Option 2: Run Locally (Java 25+)

```bash
./mvnw package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar whitekey-server/target/whitekey-server-0.0.1-SNAPSHOT.jar
```

Run the CLI:

```bash
java --enable-native-access=ALL-UNNAMED -jar whitekey-cli/target/whitekey-cli-0.0.1-SNAPSHOT.jar <command>
```

Default endpoints and paths:
- **Agent Proxy:** `http://127.0.0.1:9999` (`PROXY_ADDRESS`, `PROXY_PORT`, `PROXY_ENABLED`)
- **Admin API:** `http://127.0.0.1:1111` (`BIND_ADDRESS`, `PORT`)
- **Data Directory:** `~/.local/share/whitekey` (`WHITEKEY_DATA_DIR`)

## Configuration

Set via environment variables or `.env`:

| Variable | Default (Local) | Default (Docker) | Description |
| :--- | :--- | :--- | :--- |
| `PORT` | `1111` | `1111` | Admin API port |
| `BIND_ADDRESS` | `127.0.0.1` | `127.0.0.1` | Admin API bind address |
| `PROXY_ENABLED` | `true` | `true` | Enable or disable the proxy server |
| `PROXY_PORT` | `9999` | `9999` | Agent proxy port |
| `PROXY_ADDRESS` | `127.0.0.1` | `0.0.0.0` | Agent proxy bind address |
| `PROXY_HOST_BIND` | — | `127.0.0.1` | Host IP for Docker Compose port mapping |
| `DEFAULT_POLICY` | `DENY` | `DENY` | Fallback policy for unmatched requests (`DENY`, `ALLOW`, or `REQUIRE_APPROVAL`) |
| `WHITEKEY_DATA_DIR` | `~/.local/share/whitekey` | `/root/.local/share/whitekey` | SQLite DB, token, and key directory |
| `WHITEKEY_ADMIN_TOKEN` | auto-generated | auto-generated | Override admin bearer token |

---

## Using the Proxy

### URL-Prefix Mode (Credential Injection + Full L7 Policy Inspection)

Prefix target URLs with the WhiteKey proxy address:

```bash
curl http://127.0.0.1:9999/https://api.github.com/user
```

For AI SDKs (e.g., OpenAI / Anthropic), point `base_url` to the proxy with a dummy key:

```text
http://127.0.0.1:9999/https://api.openai.com/v1
```

*(In Docker on `whitekey-sandbox`, replace `127.0.0.1` with `whitekey`.)*

### `CONNECT` Tunnel Mode

Use WhiteKey as an `HTTPS_PROXY` for raw TCP tunneling (evaluated against `CONNECT` rules; no credential injection or body inspection):

```bash
HTTPS_PROXY=http://127.0.0.1:9999 curl https://example.com
```

---

## CLI Reference

Global options (or set `WHITEKEY_SERVER_URL`, `WHITEKEY_ADMIN_TOKEN`, or `.env`):

```bash
whitekey [--server http://127.0.0.1:1111] [--token <admin-token>] <command>
```

### Status

```bash
whitekey status
```

### Setup Mode (Discover & Approve Requests Interactively)

Unmatched requests wait for human approval while setup mode is ON:

```bash
whitekey setup on --watch        # Turn ON, watch & approve/reject interactively, turn OFF on exit
whitekey setup on                # Turn ON
whitekey setup off               # Turn OFF and expire remaining unmatched pending actions
whitekey setup status            # Check ON/OFF
```

### Credentials

Built-in presets: `github`, `stripe`, `slack`, `openai`, `anthropic`.

```bash
whitekey credentials presets
whitekey credentials list

# Using a preset
whitekey credentials set --name github-pat --preset github --secret ghp_...

# Custom credential (HEADER, QUERY, or COOKIE)
whitekey credentials set --name my-api --host api.example.com --type HEADER --key X-API-Key --format "%s" --secret sk_...

whitekey credentials remove github-pat
```

### Policy Rules

Policy precedence: `DENY` (`BLOCK`) > `REQUIRE_APPROVAL` (`ASK`) > `ALLOW`. Unmatched requests follow `DEFAULT_POLICY` (`DENY` by default).

```bash
whitekey rules list

# Allow all methods/paths on a host (--paths defaults to /*)
whitekey rules add --name allow_github --effect ALLOW --hosts api.github.com

# Restrict methods, paths, query params, headers, or JSON body (CEL expression)
whitekey rules add --name limit_stripe --effect ALLOW --methods POST --hosts api.stripe.com --paths /v1/charges --condition "amount <= 5000 && currency == 'usd'"
whitekey rules add --name ask_delete --effect ASK --methods DELETE --hosts api.github.com --paths "/*"
whitekey rules add --name block_admin --effect BLOCK --hosts api.example.com --paths "/admin/*"

whitekey rules remove allow_github
```

### Approvals

```bash
whitekey approvals list
whitekey approvals watch [--interval 300]
whitekey approvals approve <action-uuid> [--save-rule]
whitekey approvals reject <action-uuid>
```

### History

```bash
whitekey history [--limit 20] [--status ALLOWED|DENIED|AWAITING_APPROVAL|EXPIRED|CANCELLED|FAILED] [--method GET] [--host api.github.com]
whitekey history clear
```
