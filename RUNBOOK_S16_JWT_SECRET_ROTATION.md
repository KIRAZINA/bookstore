# RECOVERED FROM GIT HISTORY

This file was recovered from commit 54bcc10 on 2026-11-18. It was previously
claimed in AUDIT_REGISTER.md as "reconstruction added" but was not on disk
at HEAD. The content is the version committed at 54bcc10 and MUST be verified
against the target environment before use. See RES-S16-RUNBOOK-MISSING.
# RUNBOOK S16 — JWT Signing Secret Rotation

> RECONSTRUCTED — the original runbook was created in Wave 1B but is absent from the
> working tree and from all Git history. This reconstruction is based on the
> curator-provided S16 context and the current repository configuration.
> An operator must verify these steps against the target environment before use.

## 1. Purpose

Rotate the application's JWT signing secret after the previous value was removed from
tracked Kubernetes configuration.

The application reads the signing secret from the `JWT_SECRET` environment variable.
The expected algorithm is HMAC-SHA256, so the secret must provide at least 256 bits of
entropy.

## 2. Scope and impact

- Applies to every deployed environment: dev, staging, and production.
- Rotating the signing secret invalidates all tokens signed with the previous secret.
- Users with active sessions will need to authenticate again.
- Do not commit the new secret to Git, Helm values, Kustomize overlays, or example files.

## 3. Preconditions

1. Access to the deployment secret manager or cluster secret store for the target
   environment.
2. Access to the deployment pipeline or Kubernetes rollout controls.
3. A coordination window for user-visible re-authentication.
4. A secure place to retain the previous secret temporarily for rollback.

## 4. Generate a new secret

Use a cryptographically secure random generator. For example:

```bash
openssl rand -base64 48
```

Do not reuse the previously committed value. Treat the old value as compromised.

## 5. Store the secret outside Git

Store the generated value in the environment's secret manager or Kubernetes Secret
provisioned out-of-band. The tracked `k8s/secret.yaml` intentionally does not contain
`JWT_SECRET`.

For local development, place the value in an untracked `.env` file or the local secret
manager. Never add it to `application*.yml`, `k8s/secret.example.yaml`, or any tracked
manifest.

## 6. Deploy the new secret

1. Update the `JWT_SECRET` entry in the target environment's secret store.
2. Trigger a rollout or restart so every application instance reads the new value.
3. Confirm that all instances are running with the same secret version.
4. Verify the application starts without JWT configuration errors.

## 7. Verify rotation

1. Confirm that a token issued before rotation is rejected.
2. Confirm that a newly issued token is accepted.
3. Confirm that login and protected endpoints work for a test account.
4. Confirm that no instance is still using the previous secret.
5. Monitor authentication errors during the rollout window.

## 8. Rollback

If the rollout fails:

1. Restore the previous secret in the target environment's secret store.
2. Restart or roll back the application deployment.
3. Confirm authentication works again.
4. Investigate the failure before retrying rotation.

Rollback restores the previous secret and therefore restores acceptance of tokens
signed with it. Use rollback only within the approved incident window.

## 9. Post-rotation cleanup

1. Remove the previous secret from the secret store after the rollback window closes.
2. Confirm that no tracked file contains `JWT_SECRET` values.
3. Confirm that `.gitignore` still excludes local secret files.
4. Record the rotation date and operator in the environment's change log.
5. If the old value was ever committed, assume it is compromised. History rewriting is
   a separate security decision and is not required for rotation to be effective.

## 10. Evidence and references

- S16 register entry: JWT secret was committed in tracked Kubernetes configuration.
- The secret was removed from `k8s/secret.yaml`.
- JWT defaults were removed from `application-dev.yml` and `application-perf.yml`.
- `k8s/secret.example.yaml` documents the required key without a value.
- Overlay comments state that `JWT_SECRET` is provisioned out-of-band.
