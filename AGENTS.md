# PiggieTV release versioning

- For each parent user request that materially changes tracked product files in this repository, bump the PiggieTV app version exactly once after the edits and before the final build, test, install, or deployment.
- Run `powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1` for that bump. Do not hand-edit or concatenate version digits.
- Do not bump for read-only work, status or clarification replies, requests that produce no tracked product change, retries or continuations of the same parent request, or prompts sent to child agents. Multi-agent work receives one bump from the parent request.
- The patch and minor fields are decimal counters from 0 through 99. After `0.0.99`, the next version is `0.1.0`.
- Never advance the major field automatically. When the current version is `x.99.99`, stop and require explicit user authorization for the next major release.
- Bump only the app release version. API, schema, contract, Android SDK, dependency, and server-compatibility versions follow their own change policies.
