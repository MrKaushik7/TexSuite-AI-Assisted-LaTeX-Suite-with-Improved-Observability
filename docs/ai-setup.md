# Optional AI assistance

[Home](../README.md) · [Task guide](user-guide.md) · [Troubleshooting](troubleshooting.md)

Rename and text replacement work with manual review. Cleanup and merge are
always offline. AI proposes which eligible occurrences match your meaning;
it cannot authorize writes or change your literal replacement.

**For better results, use a more capable model.** AI can make semantic mistakes;
review every proposed change.

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
requesting up to **8,192 output tokens**, including reasoning where the provider
counts it toward output. This is a ceiling, not a fixed amount consumed.
Oversized, incomplete or structurally uncertain
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

### Budget for real papers

Offline measurements of 37 batches from three real LaTeX projects suggest these
sizes, using a rough **2–4 characters per token** estimate:

| Work | Estimated tokens |
| --- | --- |
| One batch's input, including instructions and evidence | 1,100–5,900 |
| Eight decisions' final JSON, with short to maximum-length ASCII reasons | 400–1,500 |

These are sizing estimates, not tokenizer counts or measured provider usage.
Reasoning is additional to the final JSON and shares the **8,192-token output
ceiling** on OpenRouter. The selected Nemotron free preset also requests a separate
**2,560-token reasoning limit**, leaving 5,632 of the total allowance outside
thinking. Increasing total output alone can still let reasoning consume it all.
See [OpenRouter's budget control](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#max-tokens-for-reasoning).
Other/custom models retain their provider's reasoning defaults.

Budget applies **per request**, not per document. A 100-candidate chapter in the
same set needs 13 batches: roughly 24,000–48,000 input tokens in total and an
output ceiling of **106,496 tokens**, before any separately approved schema repair.
Actual consumption can be much lower; cached decisions avoid repeat calls.
More reasoning can take longer or reach your configured timeout. Paid models
can cost more; free endpoints still have quotas and availability limits.

### Incomplete responses

A large or complex request can fail on a free endpoint even within TexSuite's
local context limits. For example:

```text
AI classification stopped: INCOMPLETE. OpenRouter did not return a completed response.
All AI decisions for this operation were discarded.
Continue with manual review? [y/N]:
```

This reports an unfinished provider response. Payload size is one possible cause;
the message alone does not establish it. TexSuite now allows **8,192 output tokens**
per request, increased from 1,500; even this can be exhausted before classification
finishes. On reasoning models, thinking can consume that budget before usable JSON is produced; see
[OpenRouter's token-budget explanation](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#reasoning-tokens-and-max_output_tokens).
Provider errors can also cause an incomplete response. Free access has
endpoint-specific limits and availability; it does not guarantee completion.

OpenRouter failures now append safe diagnostics: a known `finish_reason`, the
requested `output_limit`, available prompt/completion/reasoning token counts and
a numeric choice-error code when supplied. Unknown reasons display `unknown`;
missing or invalid counts are omitted. Raw provider text is excluded. Interpret
the fields using [OpenRouter's response contract](https://openrouter.ai/docs/api_reference/overview#finish-reason):

| Result | Next step |
| --- | --- |
| `finish_reason=length` | The provider reports a length limit. Compare `completion_tokens` with `output_limit`; if all completion tokens are reasoning, no allowance remained for final JSON. The Nemotron preset requests a separate thinking limit. A longer timeout does not raise token allowances. |
| `finish_reason=error` or `provider_error=true` | Inspect the numeric code if present. This is a provider failure; elapsed time alone does not establish a provider timeout. |
| `TIMEOUT` | TexSuite reached its configured deadline. Increase the timeout only if you want to wait longer. |
| `finish_reason=unknown` or missing usage | The response lacks usable diagnostic fields; its cause is still unconfirmed. |

Enter `y` to review occurrences manually, or Enter/`n` to cancel. No edits have
been applied by this failed operation; all its AI suggestions, including earlier
batches, are discarded. Manual selection still needs the normal preview,
approval and validation before writing.

For another AI attempt, choose a smaller chapter/file scope, or a narrower region
for Replace text. If it still fails, use manual review or configure another model.
TexSuite does not automatically truncate context or retry an incomplete response.
Increasing the timeout does not increase the output-token budget.

### Output modes and provider policies

- OpenRouter JSON Schema mode requests strict schema and `require_parameters:true`.
  Unsupported routes fail without automatic downgrade.
- The selected Nemotron preset defaults to **prompt JSON** because its endpoint
  does not support `response_format`. The schema is placed in instructions;
  strict local validation still applies, without provider-enforced formatting.
  Other OpenRouter models default to JSON Schema; the mode remains overridable.
  The preset's reasoning budget is included in the exact payload preview; an
  unsupported control fails without silently removing it. A changed payload also
  changes cache identity, so old-policy decisions are not reused as new results.
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
