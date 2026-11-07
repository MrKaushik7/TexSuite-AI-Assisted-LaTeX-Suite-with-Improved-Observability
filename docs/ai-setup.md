# Optional AI assistance

[Home](../README.md) · [Task guide](user-guide.md) · [Troubleshooting](troubleshooting.md)

Rename and text replacement work with manual review. Cleanup and merge are
always offline. AI proposes which eligible occurrences match your meaning;
it cannot authorize writes or change your literal replacement.

## Configure a provider

1. Choose **5: Settings → 8: AI provider settings and test → 1: Change AI settings**.
   First interactive startup also offers setup; Enter there keeps manual use available.
2. Choose **1 OpenAI**, **2 OpenRouter** or **3 Gemini**.
3. At the model prompt, Enter accepts the preset below. Type another model ID
   to override it, or `off` to save AI as disabled.
4. Enter the **name** of your key environment variable, not its secret value.
   Enter accepts the provider's default name. Timeout defaults to 60 seconds.
5. Accept the output-mode default unless your chosen endpoint requires another mode.

| Provider | Preset model | Default key variable | API destination |
| --- | --- | --- | --- |
| OpenAI | `gpt-6-luna` | `OPENAI_API_KEY` | `https://api.openai.com/v1/responses` |
| OpenRouter | `nvidia/nemotron-3-ultra-550b-a55b:free` | `OPENROUTER_API_KEY` | `https://openrouter.ai/api/v1/chat/completions` |
| Gemini | `gemini-2.5-flash` | `GEMINI_API_KEY` | `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` |

These are setup presets, not proof of account access, quota, billing or accuracy.
Saved profiles are unchanged until reconfigured. Settings save globally immediately
and apply across sessions/projects. Saved disable skips future startup offers.
Setup makes no API request; failed persistence leaves the previous profile active.

### Export the key and restart

Quit TexSuite, then export your actual key in the terminal you will use to relaunch.
For example, for Gemini:

```sh
export GEMINI_API_KEY='your-api-key'
texsuite path/to/main.tex
```

Use the variable name selected during setup. Replace the placeholder with your
key; do not enter the value in TexSuite's key-reference prompt. A running session
cannot receive later exports. Startup checks for a readable, nonempty printable
ASCII value without spaces, never prints the secret and makes no API request.
Missing/unreadable/malformed keys leave manual operations available.

A readable key may be invalid or expired. Choose **Settings → 8 → 2: Test provider**
to send fixed synthetic text after separate consent. The test bypasses the cache.

### Profiles and environment defaults

Private global profile locations:

- macOS: `~/Library/Application Support/TexSuite/ai.json`
- Other systems: `~/.local/state/texsuite/ai.json`

Profiles store provider settings and the key variable's name, never its value.
Saved fields override environment defaults:

- `TEXSUITE_MODEL_PROVIDER`: `openai`, `openrouter` or `gemini`
- `TEXSUITE_<PROVIDER>_MODEL`, `_KEY_ENV`, `_TIMEOUT`, `_OUTPUT_MODE`
- Output mode: `json_schema`, or OpenRouter-only `prompt_json`

A configured environment profile also skips startup setup. Legacy project
`.tex-suite/config.json` AI profiles are ignored; configure global preferences in
Settings. Invalid or unsavable settings are reported without adopting an unsaved profile.

## What is sent

Every request displays model, destination and **exact outgoing JSON** without the
key. Only `y` permits sending it. Review all included source/context before consenting.
Native Gemini puts the key in `x-goog-api-key`, not the URL.

Rename context includes the enclosing equation/blank-line paragraph, nearest
supported section/definition/theorem/proof and referenced local macro definitions.
Static includes can supply read-only context. `--debug` lists retrieved slices.
Batches contain at most **8 candidates** and **24,000 Unicode source characters**,
requesting **1,500 output tokens**. Oversized, incomplete or structurally uncertain
context requires manual review, without silent truncation. See
[exact context and transport limits](../opinions.md#context-selection-and-budgets).

Requests are bounded and non-streaming, without tools or conversation history.
Native OpenAI uses `store:false`; this does not override provider retention policies.
All batches must validate before review. HTTP errors, refusals, timeouts or invalid/
incomplete output discard the operation's AI suggestions and offer manual review
or cancellation.

A completed malformed classification can offer **one schema repair**, with separate
consent. Its payload contains allowed IDs and previous output, which can repeat
source, without reattaching original excerpts. Local providers are future scope.
Both rename and replacement support AI batch selection and manual fallback.

### Output modes and provider policies

- OpenRouter JSON Schema mode requests strict schema and `require_parameters:true`.
  Unsupported routes fail without automatic downgrade.
- The selected Nemotron preset defaults to **prompt JSON** because its endpoint
  does not support `response_format`. The schema is placed in instructions;
  strict local validation still applies, without provider-enforced formatting.
  Other OpenRouter models default to JSON Schema; the mode remains overridable.
- Gemini requests `generationConfig.responseMimeType` and `responseJsonSchema`,
  following the [Gemini SDK example](https://googleapis.github.io/python-genai/#json-response-schema).

The [Nemotron endpoint](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free)
documents logging submitted data. Start with synthetic text. TexSuite specifies
one model without an alternate paid model. Free access depends on availability,
account tier and quota; it cannot force free billing on a paid Gemini account.
Google's free tier may use submitted data to improve products. Check provider
policies before sending private source:
[OpenAI model](https://developers.openai.com/api/docs/models/gpt-6-luna),
[Gemini model](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash),
[Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-2.5-flash).
Gemini 2.5 access may be restricted to accounts with prior use; custom IDs remain available.

## Reuse or regenerate decisions

Validated rename/replacement classifications are cached privately per project in
`.tex-suite/cache/`. Repeating an unchanged request after cancellation or restart
can avoid another API call. A hit explicitly says **no remote call**; it still
requires current review, approval, freshness checks and compilation.
Manual replacement does not consult the cache. Cache records never grant apply permission.

| Action | Where |
| --- | --- |
| Inspect counts/size, enable/disable or confirmed clear | **Settings → 10: AI response cache** |
| Bypass all cache reads for one operation | **3: AI regenerate** at occurrence selection |

Enable/disable persists per project; disabling keeps records. Clear removes only
result files, preserving settings/history/backups. Regenerate uses normal remote
consent and replaces an old entry with a valid result when caching is enabled.

Cache keys include operation/scope/region/matching mode, request/context identities,
actual JSON, prompt/schema, endpoint/model and generation settings. Exact source
and replacement whitespace matter: `hi` and ` hi ` differ. Relevant source, meaning
or model-setting changes cause misses. Credentials are not in request JSON or cache;
explanations can quote private source, so do not publish this directory.

Storage allows **256 result files**, **128 KiB each** (**32 MiB** total managed results).
A full cache still returns new results without saving new entries; existing keys
can be refreshed. Corrupt/incompatible entries are misses. Unavailable storage
never repeats a completed API call or bypasses validation. Earlier successful
batches may be cached even when a later batch fails; partial decisions cannot
authorize edits. Successful schema repairs are saved under the original request.
