/**
 * CodeBuddy Message Service
 *
 * Bridges the CodeBuddy Agent SDK (@tencent-ai/agent-sdk) into the plugin's
 * tagged stdout protocol. Because CodeBuddy's message model mirrors the
 * Anthropic one (system / assistant / user / result, with text / tool_use /
 * tool_result content blocks), we forward those messages verbatim as
 * [MESSAGE] and reuse the existing Claude stream pipeline (daemon ->
 * ClaudeStreamAdapter) on the Java side — no new parsing logic required.
 *
 * CodeBuddy-only auxiliary messages (file-history-snapshot, task_*,
 * topic, ai-title, tool_progress) are dropped to keep the stream clean.
 *
 * Streaming text/thinking deltas (PartialAssistantMessage) are translated to
 * [CONTENT_DELTA] / [THINKING_DELTA] for live token-by-token rendering.
 */
import { query } from '@tencent-ai/agent-sdk';

// Top-level message types forwarded to the host as [MESSAGE].
// Everything else (file-history-snapshot, task_*, topic, ai-title,
// tool_progress, stream_event partials handled separately) is dropped.
const FORWARDED_TYPES = new Set(['system', 'assistant', 'user', 'result', 'error']);

const emit = (line) => console.log(line);
const emitMessage = (msg) => emit('[MESSAGE] ' + JSON.stringify(msg));

function buildQueryOptions({
  sessionId, cwd, permissionMode, model, reasoningEffort,
  authToken, internetEnv, extraEnv,
}) {
  const env = {};
  if (authToken) env.CODEBUDDY_AUTH_TOKEN = authToken;
  if (internetEnv) env.CODEBUDDY_INTERNET_ENVIRONMENT = internetEnv;
  if (extraEnv && typeof extraEnv === 'object') {
    for (const [k, v] of Object.entries(extraEnv)) {
      if (v !== undefined && v !== null && !Object.prototype.hasOwnProperty.call(env, k)) {
        env[k] = String(v);
      }
    }
  }

  const options = {
    maxTurns: 100,
    includePartialMessages: true,
    env,
  };
  if (cwd) options.cwd = cwd;
  if (permissionMode) options.permissionMode = permissionMode;
  if (model) options.model = model;
  if (reasoningEffort) options.effort = reasoningEffort;
  // Resume an existing conversation when a session id is supplied (multi-turn).
  if (sessionId) options.resume = sessionId;
  return options;
}

function extractAssistantText(msg) {
  if (!msg || msg.type !== 'assistant') return '';
  const content = msg.message && Array.isArray(msg.message.content) ? msg.message.content : [];
  return content
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('');
}

function extractResultText(msg) {
  if (msg && msg.type === 'result' && typeof msg.result === 'string') return msg.result;
  return '';
}

/**
 * Send a message to CodeBuddy via the Agent SDK.
 *
 * @param {object} args
 * @param {string} args.message            User prompt
 * @param {string} [args.sessionId]        Session ID to resume (omitted on first turn)
 * @param {string} [args.cwd]              Working directory
 * @param {string} [args.permissionMode]   default / acceptEdits / bypassPermissions / plan
 * @param {string} [args.model]            Model override
 * @param {string} [args.reasoningEffort]  low / medium / high / xhigh
 * @param {string} [args.authToken]        CODEBUDDY_AUTH_TOKEN (iOA OAuth token)
 * @param {string} [args.internetEnv]      CODEBUDDY_INTERNET_ENVIRONMENT (ioa/internal/...)
 * @param {object} [args.extraEnv]         Extra environment variables
 */
export async function sendMessage(args = {}) {
  const {
    message,
    sessionId = null,
    cwd = null,
    permissionMode = null,
    model = null,
    reasoningEffort = null,
    authToken = null,
    internetEnv = null,
    extraEnv = null,
  } = args;

  let streamStarted = false;
  let streamEnded = false;
  const emitStreamEndOnce = () => {
    if (streamStarted && !streamEnded) {
      streamEnded = true;
      emit('[STREAM_END]');
    }
  };

  let capturedSessionId = sessionId || '';
  let finalResult = '';

  try {
    if (!authToken) {
      throw new Error('CodeBuddy auth token is missing. Configure CODEBUDDY_AUTH_TOKEN in the provider settings.');
    }

    const options = buildQueryOptions({
      sessionId, cwd, permissionMode, model, reasoningEffort, authToken, internetEnv, extraEnv,
    });

    emit('[MESSAGE_START]');
    emit('[STREAM_START]');
    streamStarted = true;

    const q = query({ prompt: message ?? '', options });

    for await (const msg of q) {
      if (!msg || typeof msg.type !== 'string') continue;

      // Capture session id from any message that carries it.
      if (msg.session_id && !capturedSessionId) {
        capturedSessionId = msg.session_id;
      }

      // Streaming deltas (PartialAssistantMessage) -> live rendering tags.
      if (msg.type === 'stream_event' && msg.event) {
        const ev = msg.event;
        if (ev.type === 'content_block_delta' && ev.delta) {
          if (ev.delta.type === 'text_delta' && typeof ev.delta.text === 'string') {
            emit('[CONTENT_DELTA] ' + JSON.stringify(ev.delta.text));
          } else if (ev.delta.type === 'thinking_delta' && typeof ev.delta.thinking === 'string') {
            emit('[THINKING_DELTA] ' + JSON.stringify(ev.delta.thinking));
          }
        }
        continue;
      }

      // Forward canonical message types; drop CodeBuddy-only auxiliary messages.
      if (FORWARDED_TYPES.has(msg.type)) {
        emitMessage(msg);
        if (msg.type === 'assistant') {
          const t = extractAssistantText(msg);
          if (t) finalResult = t;
        } else if (msg.type === 'result') {
          if (msg.session_id) capturedSessionId = msg.session_id;
          const r = extractResultText(msg);
          if (r) finalResult = r;
        }
      }
      // file-history-snapshot / task_* / topic / ai-title / tool_progress: ignored
    }

    emitStreamEndOnce();
    emit('[MESSAGE_END]');
    emit(JSON.stringify({ success: true, sessionId: capturedSessionId, result: finalResult }));
  } catch (error) {
    emitStreamEndOnce();
    const payload = { success: false, error: error?.message || String(error) };
    emit('[SEND_ERROR] ' + JSON.stringify(payload));
    emit(JSON.stringify(payload));
  }
}
