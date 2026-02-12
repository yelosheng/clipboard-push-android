# Repository Guidelines

## Project Structure & Module Organization

- `relay_server.py` holds the Flask + Socket.IO relay server and all HTTP/Socket endpoints.
- `templates/` contains Jinja2 HTML templates (`base.html`, `login.html`, `dashboard.html`).
- `static/` hosts front-end assets (`static/js/app.js`, `static/css/style.css`).
- `requirements.txt` lists Python dependencies.
- Ops/docs live in `DEPLOY_DEBIAN.md`, `PERFORMANCE_OPTIMIZATION.md`, and `GEMINI.md`.
- Environment configuration is managed via `.env` and `.env.example`.

## Build, Test, and Development Commands

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```
Creates a local virtual environment and installs dependencies.

```powershell
python relay_server.py
```
Runs the relay server locally on port `5055` (see `relay_server.py`).

## Coding Style & Naming Conventions

- Python uses 4-space indentation and standard PEP 8 naming: `snake_case` for functions/vars and `UPPER_SNAKE_CASE` for module-level constants.
- Keep Flask route handlers small and focused; move shared logic into helper functions in `relay_server.py`.
- Front-end assets follow file naming already present under `static/`.
- No formatter or linter is configured; if you introduce one, document it here and in `requirements.txt`.

## Testing Guidelines

- No automated tests are currently configured in this repo.
- If you add tests, place them under a new `tests/` directory and use `test_*.py` naming. Document the test runner and command (e.g., `pytest`) here.

## Commit & Pull Request Guidelines

- Recent history uses Conventional Commit-style subjects like `feat: ...`. Follow this pattern (`feat:`, `fix:`, `docs:`, `chore:`) with a short, present-tense summary.
- PRs should include:
  - A clear description of the user-facing change.
  - Any required configuration updates (e.g., `.env` keys).
  - A brief test note (what you ran, or why not).

## Security & Configuration Notes

- Copy `.env.example` to `.env` and populate `R2_*`, `ADMIN_PASSWORD`, and `FLASK_SECRET_KEY` before running locally.
- The server uses Cloudflare R2 via presigned URLs; keep credentials out of source control.
