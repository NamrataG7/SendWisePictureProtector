# Contributing to SendWise

Thanks for your interest in SendWise! This is a research prototype accompanying the paper *"SendWise: Privacy-Preserving Parental Awareness of Adolescent Cyberbullying Risk through On-Device Pre-Send Intervention"* (Gaikwad & Ohatkar, 2026).

## Clone

```bash
git clone https://github.com/NamrataG7/SendWise.git
cd SendWise
```

## Parental Dashboard (Next.js 14)

```bash
cd parental-dashboard
cp .env.example .env.local   # then fill in values — see .env.example comments
npm install
npm run dev                  # http://localhost:3000
```

Type-check without running: `npx tsc --noEmit`. This is what CI enforces.

## Model Training (Python 3.12)

```bash
cd model_training
pip install -r requirements.txt
python train_sendwise_rf.py
```

Produces `training_report.md`, `MODEL_CARD.json`, and the on-device model artefact under `artifacts/`. Retraining is deterministic (`random_state=42`).

## Android App (SafeKeyboardApp)

Local Android Studio builds are supported but **not required**. The recommended path is:

- Push to `main` (or open a PR) → GitHub Actions builds the debug APK automatically.
- Tag `vX.Y.Z` → GitHub Actions publishes a Release with the APK attached.

See [`BUILD_APK.md`](BUILD_APK.md) and [`INSTALL_ON_REDMI.md`](INSTALL_ON_REDMI.md).

## Commit Style

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(dashboard): add per-category filter to timeline
fix(ime): guard against null editorInfo on Android 14
docs(readme): add reproducibility section
chore(ci): bump setup-node to v4
```

Types we use: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `ci`.

## Pull Request Checklist

- [ ] Local tests pass (`npm test` for dashboard where applicable; `python train_sendwise_rf.py` completes without error for model changes).
- [ ] `npx tsc --noEmit` passes in `parental-dashboard/`.
- [ ] No secrets, tokens, `.env*` files, keystores, or personal data included in the diff.
- [ ] Docs updated if the API surface, env vars, or CLI/build steps changed (`README.md`, `.env.example`, relevant `*.md`).
- [ ] Commit messages follow Conventional Commits.
- [ ] Security-sensitive changes reference [`SECURITY.md`](SECURITY.md).

## Reporting Security Issues

Do **not** open a public issue. See [`SECURITY.md`](SECURITY.md) for the private disclosure process.
